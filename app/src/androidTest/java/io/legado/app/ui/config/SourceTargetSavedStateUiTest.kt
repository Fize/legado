package io.legado.app.ui.config

import android.content.Intent
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.SourceTargetStateStore
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.source.manage.sourceTargetTaskId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SourceTargetSavedStateUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = SourceTargetStateStore(File(context.noBackupFilesDir, "source-target-state"))
    private fun size(state: Bundle): Int = Parcel.obtain().let {
        try { it.writeBundle(state); it.dataSize() } finally { it.recycle() }
    }
    @Suppress("UNCHECKED_CAST")
    private fun selection(activity: BookSourceActivity) = BookSourceActivity::class.java
        .getDeclaredField("selectedUrlsState\$delegate").apply { isAccessible = true }
        .get(activity) as MutableState<Set<String>>
    private fun groups(activity: BookSourceActivity) = BookSourceActivity::class.java
        .getDeclaredField("groupEditTargetsState").apply { isAccessible = true }

    @Test fun largeTargetsKeepSmallSavedStateAndExactOriginalSelectionAfterTwoRecreations() {
        val urls = List(8_000) { "https://owned-state-probe.invalid/source/$it/" + "a".repeat(210) }
        val report = JSONObject().put("scope", "Synthetic identities prepared through private UI state; actual save callback, Fragment restoration and private disk read; not a full 8000-source import")
            .put("targets", urls.size)
        var owner = ""
        var dialogOwner = ""
        ActivityScenario.launch<BookSourceActivity>(Intent(context, BookSourceActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                selection(activity).value = urls.toSet()
                groups(activity).set(activity, urls.reversed())
                val dialog = SourceCheckDialog.create("large fixture", urls.take(7_000), activity.taskId)
                dialog.showNow(activity.supportFragmentManager, "large-source-targets")
                val state = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, state)
                assertFalse(state.containsKey("selectedSourceUrls"))
                assertFalse(state.containsKey("groupEditTargets"))
                val reference = state.getString("sourceTargetsSnapshot")!!
                owner = reference.substringBefore('/')
                assertEquals(listOf(urls, urls.reversed()), store.read(reference))
                val args = dialog.requireArguments()
                assertFalse(args.containsKey("targets"))
                val dialogReference = args.getString("targetSnapshot")!!
                dialogOwner = dialogReference.substringBefore('/')
                assertEquals(listOf(urls.take(7_000)), store.read(dialogReference))
                report.put("dialog_argument_bytes", size(args)).put("activity_and_fragment_saved_state_bytes", size(state))
                assertTrue("The whole Activity and Fragment state must stay small", size(state) < 50 * 1024)
                assertTrue(size(args) < 1024)
            }
            repeat(2) { round ->
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(urls.toSet(), selection(activity).value)
                    assertEquals(urls.reversed(), groups(activity).get(activity))
                    val dialog = activity.supportFragmentManager.findFragmentByTag("large-source-targets") as SourceCheckDialog
                    assertEquals(listOf(urls.take(7_000)), store.read(dialog.requireArguments().getString("targetSnapshot")!!))
                    val state = Bundle()
                    instrumentation.callActivityOnSaveInstanceState(activity, state)
                    assertTrue(size(state) < 50 * 1024)
                    report.put("restored_state_bytes_$round", size(state))
                }
            }
        }
        assertFalse(File(context.noBackupFilesDir, "source-target-state/$owner").exists())
        assertFalse(File(context.noBackupFilesDir, "source-target-state/$dialogOwner").exists())
        report.put("status", "passed").put("recreations", 2).put("exact_target_sets_preserved", true)
            .put("owners_removed_when_host_finishes", true)
        File(context.cacheDir, "source-target-large-state.json").writeText(report.toString(2))
    }

    @Test fun missingDialogSnapshotCannotChangeKeywordOrStartARequest() {
        val before = CheckSource.keyword
        assertFalse(Debug.isChecking)
        ActivityScenario.launch<BookSourceActivity>(Intent(context, BookSourceActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = SourceCheckDialog.create(before, listOf("https://owned-missing.invalid"), activity.taskId)
                val reference = dialog.requireArguments().getString("targetSnapshot")!!
                store.release(store.claim(reference))
                dialog.showNow(activity.supportFragmentManager, "missing-source-targets")
            }
            compose.onNodeWithText("无法恢复校验目标，请关闭后重新选择书源").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextReplacement("must-not-submit")
            compose.onNodeWithText(context.getString(R.string.ok)).performClick()
            compose.waitForIdle()
            assertEquals(before, CheckSource.keyword)
            assertFalse(Debug.isChecking)
        }
        File(context.cacheDir, "source-target-missing-state.json").writeText(
            JSONObject().put("status", "passed").put("keyword_unchanged", true).put("no_check_session_started", true).toString(2))
    }

    @Test fun legacyInlineTargetsBecomeSmallReferencesAndSurviveRecreation() {
        val urls = List(8_000) { "https://owned-legacy.invalid/$it/" + "a".repeat(210) }
        val report = JSONObject().put("targets", urls.size)
        ActivityScenario.launch<BookSourceActivity>(Intent(context, BookSourceActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = SourceCheckDialog().apply {
                    arguments = Bundle().apply {
                        putString("keyword", "legacy-draft")
                        putStringArrayList("targets", ArrayList(urls))
                    }
                }
                dialog.showNow(activity.supportFragmentManager, "legacy-inline-targets")
                val args = dialog.requireArguments()
                assertFalse(args.containsKey("targets"))
                assertEquals(listOf(urls), store.read(args.getString("targetSnapshot")!!))
                val state = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, state)
                report.put("argument_bytes", size(args)).put("saved_state_bytes", size(state))
                assertTrue(size(state) < 50 * 1024)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("legacy-inline-targets") as SourceCheckDialog
                assertEquals(listOf(urls), store.read(dialog.requireArguments().getString("targetSnapshot")!!))
                assertEquals("legacy-draft", dialog.requireArguments().getString("keyword"))
            }
        }
        report.put("status", "passed").put("recreations", 1).put("exact_targets_preserved", true)
        File(context.cacheDir, "source-target-legacy-conversion.json").writeText(report.toString(2))
    }

    @Test fun failedLegacyMigrationDropsInlineTargetsAndCannotSubmit() {
        val originalKeyword = CheckSource.keyword
        assertFalse(Debug.isChecking)
        val urls = List(8_000) { "https://owned-legacy-fault.invalid/$it/" + "a".repeat(210) }
        val report = JSONObject().put("targets", urls.size)
            .put("fault", "Temporarily replace this test installation's snapshot directory with an owned regular file; restore it in finally")
        ActivityScenario.launch<BookSourceActivity>(Intent(context, BookSourceActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val directory = File(context.noBackupFilesDir, "source-target-state")
                val retained = File(context.noBackupFilesDir, "owned-source-target-fault-${java.util.UUID.randomUUID()}")
                val args = Bundle().apply {
                    putString("keyword", originalKeyword)
                    putStringArrayList("targets", ArrayList(urls))
                }
                assertTrue(directory.renameTo(retained))
                try {
                    directory.writeText("owned-source-target-fault")
                    SourceCheckDialog().apply { arguments = args }
                        .showNow(activity.supportFragmentManager, "failed-legacy-targets")
                    val state = Bundle()
                    instrumentation.callActivityOnSaveInstanceState(activity, state)
                    report.put("argument_bytes", size(args)).put("saved_state_bytes", size(state))
                        .put("inline_targets_retained", args.containsKey("targets"))
                        .put("page_failure_reported", state.getBoolean("sourceTargetsUnavailable"))
                    File(context.cacheDir, "source-target-legacy-fault.json").writeText(report.toString(2))
                    assertTrue(state.getBoolean("sourceTargetsUnavailable"))
                    assertFalse("A failed migration must not retain the oversized inline list", args.containsKey("targets"))
                    assertTrue(size(state) < 50 * 1024)
                } finally {
                    // Also bound the deliberately failing baseline's state during test cleanup.
                    args.remove("targets")
                    if (directory.isFile) {
                        assertEquals("owned-source-target-fault", directory.readText())
                        assertTrue(directory.delete())
                    }
                    assertTrue(retained.renameTo(directory))
                    report.put("snapshot_directory_restored", true)
                    File(context.cacheDir, "source-target-legacy-fault.json").writeText(report.toString(2))
                }
            }
            scenario.recreate()
            compose.onNodeWithText("无法恢复校验目标，请关闭后重新选择书源").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextReplacement("must-not-submit-legacy")
            compose.onNodeWithText(context.getString(R.string.ok)).performClick()
            compose.waitForIdle()
            assertEquals(originalKeyword, CheckSource.keyword)
            assertFalse(Debug.isChecking)
        }
        report.put("status", "passed").put("recreations", 1).put("keyword_unchanged", true)
            .put("no_check_session_started", true)
        File(context.cacheDir, "source-target-legacy-fault.json").writeText(report.toString(2))
    }

    @Test fun inactiveRecentTaskKeepsItsPrivateSnapshotDuringCleanup() {
        val root = File(context.noBackupFilesDir, "owned-source-task-id-${java.util.UUID.randomUUID()}")
        val snapshots = SourceTargetStateStore(root)
        val owner = snapshots.create(12345)
        try {
            val reference = snapshots.save(owner, listOf(listOf("owned-inactive-target")))
            @Suppress("DEPRECATION")
            val info = ActivityManager.RecentTaskInfo().apply {
                id = -1
                persistentId = 12345
                if (Build.VERSION.SDK_INT >= 29) taskId = 12345
            }
            snapshots.pruneRemovedTasks(setOf(sourceTargetTaskId(info)))
            assertEquals(listOf(listOf("owned-inactive-target")), snapshots.read(reference))
            File(context.cacheDir, "source-target-inactive-task.json").writeText(JSONObject()
                .put("status", "passed").put("api", Build.VERSION.SDK_INT)
                .put("scope", "Synthetic inactive Recents record; production task-ID accessor and actual private snapshot cleanup; not a real killed task")
                .put("selected_task_id", sourceTargetTaskId(info)).put("snapshot_preserved", true).toString(2))
        } finally {
            snapshots.release(owner)
            assertTrue(root.delete())
        }
    }
}
