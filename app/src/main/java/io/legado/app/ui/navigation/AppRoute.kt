package io.legado.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The complete, typed navigation contract for the Compose application shell.
 *
 * Routes carry only stable identifiers. Screen data is always rebuilt from a repository.
 */
@Serializable
sealed interface AppRoute : NavKey

@Serializable
sealed interface TopLevelRoute : AppRoute

@Serializable
data object BookshelfRoute : TopLevelRoute

@Serializable
data object ExploreRoute : TopLevelRoute

@Serializable
data object MyRoute : TopLevelRoute

@Serializable
data class BookInfoRoute(
    val bookUrl: String,
    val requestId: String = java.util.UUID.randomUUID().toString(),
    val previewKey: String = requestId,
    val returnToReader: Boolean = false,
) : AppRoute

@Serializable
data class EditBookInfoRoute(val bookUrl: String, val bookInfoChildRequestId: String? = null) : AppRoute

@Serializable
data object BookshelfSearchRoute : AppRoute

@Serializable
data class SearchRoute(val query: String = "", val scope: String? = null) : AppRoute

@Serializable
data class ExploreBooksRoute(
    val sourceUrl: String,
    val title: String = "",
    val exploreUrl: String = "",
) : AppRoute

@Serializable
data class BookTocRoute(
    val bookUrl: String,
    val openTxtTocRules: Boolean = false,
    val readerCatalogRequestId: String? = null,
    val bookInfoChildRequestId: String? = null,
) : AppRoute {
    init { require(readerCatalogRequestId == null || bookInfoChildRequestId == null) }
}

@Serializable
data class TextReaderRoute(
    val bookUrl: String,
    val chapterIndex: Int? = null,
    val requestId: String? = null,
    val returnToDetails: Boolean = false,
) : AppRoute

/** Default text/PDF reader, hosted by the existing mature Activity. */
@Serializable
data class PlatformTextReaderRoute(val bookUrl: String, val requestId: String) : AppRoute

@Serializable
data class MangaReaderRoute(
    val bookUrl: String,
    val chapterIndex: Int? = null,
    val pageIndex: Int = 0,
    val inBookshelf: Boolean = true,
    val chapterChanged: Boolean = false,
    val requestId: String = java.util.UUID.randomUUID().toString(),
    val returnToDetails: Boolean = false,
) : AppRoute

@Serializable
data class AudioPlayerRoute(val bookUrl: String, val requestId: String? = null, val returnToDetails: Boolean = false) : AppRoute

@Serializable
data class VideoPlayerRoute(val bookUrl: String, val chapterIndex: Int? = null,
    val requestId: String? = null, val returnToDetails: Boolean = false) : AppRoute

@Serializable
data class SettingsRoute(val section: SettingsSection = SettingsSection.Root) : AppRoute

@Serializable
enum class SettingsSection {
    Root,
    Backup,
    Cover,
    CoverFont,
    Other,
    Reader,
    Theme,
    ReadAloud,
}

@Serializable
data object AboutRoute : AppRoute

@Serializable
data object AppLogRoute : AppRoute

@Serializable
data object ReadRecordRoute : AppRoute

@Serializable
data object AllBookmarksRoute : AppRoute

@Serializable
data class BookshelfManageRoute(val groupId: Long = -1L) : AppRoute

@Serializable
data object BookGroupsRoute : AppRoute

@Serializable
data object ImportLocalBookRoute : AppRoute

@Serializable
data object RemoteBookRoute : AppRoute

@Serializable
data object RemoteServersRoute : AppRoute

@Serializable
data class RemoteServerEditRoute(val serverId: Long? = null) : AppRoute

@Serializable
data class CacheRoute(val groupId: Long=-1) : AppRoute

@Serializable
data object BookSourceManageRoute : AppRoute

@Serializable
data class BookSourceEditRoute(val sourceUrl: String? = null) : AppRoute

@Serializable
data class JavaScriptSourceEditRoute(val sourceUrl: String? = null) : AppRoute

@Serializable
data class SourceLoginRoute(
    val target: SourceLoginTarget,
    val key: String? = null,
    val bookType: Int = 0,
    val bookUrl: String? = null,
) : AppRoute

@Serializable
enum class SourceLoginTarget {
    CurrentReading,
    BookSource,
    HttpTts,
}

@Serializable
data class BrowserRoute(val url: String, val title: String = "") : AppRoute

@Serializable
data class OpenUrlConfirmRoute(
    val uri: String,
    val mimeType: String? = null,
    val sourceOrigin: String? = null,
    val sourceName: String? = null,
    val sourceType: Int = 0,
) : AppRoute

@Serializable
data class OnlineImportRoute(val uri: String) : AppRoute

@Serializable
data class FileAssociationRoute(val requestId: String, val sourceUri: String? = null) : AppRoute

@Serializable
data class AddToBookshelfRoute(val bookUrl: String) : AppRoute

@Serializable
data class BookSourceImportRoute(
    val source: String,
    val reimportBookUrl: String? = null,
    val reimportSourceUrl: String? = null,
) : AppRoute

@Serializable
data class VerificationCodeRoute(
    val imageUrl: String,
    val sourceOrigin: String? = null,
    val sourceName: String? = null,
    val sourceType: Int = 0,
    val verificationResultKey: String? = null,
) : AppRoute

@Serializable
data class CodeEditorRoute(val documentKey: String) : AppRoute

@Serializable
data object QrCodeRoute : AppRoute

@Serializable
data object ReplaceRuleRoute : AppRoute

@Serializable
data object RuleSubscriptionRoute : AppRoute

@Serializable
data class ReplaceRuleEditRoute(
    val ruleId: Long? = null,
    val pattern: String? = null,
    val isRegex: Boolean = false,
    val scope: String? = null,
) : AppRoute

@Serializable
data object HighlightRuleRoute : AppRoute

@Serializable
data class HighlightRuleEditRoute(val ruleId: Long? = null,val requestId: String = java.util.UUID.randomUUID().toString()) : AppRoute

@Serializable
data object DictionaryRuleRoute : AppRoute

@Serializable
data object TextTocRuleRoute : AppRoute

@Serializable
data object FileManagerRoute : AppRoute

@Serializable
data class FilePickerRoute(val requestKey: String) : AppRoute

@Serializable
data class SearchContentRoute(
    val bookUrl: String,
    val query: String = "",
    val searchResultKey: String? = null,
    val initialIndex: Int = 0,
    val sessionId: String? = null,
    val readerCatalogRequestId: String? = null,
) : AppRoute

@Serializable
data class ImportRoute(val payload: String, val kind: ImportKind, val requestId: String = java.util.UUID.randomUUID().toString(),val parentManagerId: String?=null) : AppRoute

@Serializable
enum class ImportKind {
    BookSource,
    DictionaryRule,
    HighlightRule,
    HttpTts,
    ReplaceRule,
    TextTocRule,
}

val TopLevelRoutes: Set<AppRoute> = setOf(BookshelfRoute, ExploreRoute, MyRoute)

@Serializable
data class PhotoViewerRoute(val requestKey: String) : AppRoute
