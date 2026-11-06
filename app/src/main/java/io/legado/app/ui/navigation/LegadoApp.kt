package io.legado.app.ui.navigation

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.help.config.ThemeConfig
import io.legado.app.R
import io.legado.app.ui.about.AboutScreen
import io.legado.app.ui.about.AppLogScreen
import io.legado.app.ui.about.ReadRecordDestination
import io.legado.app.ui.about.ReadRecordScreen
import io.legado.app.ui.about.performAboutAction
import io.legado.app.ui.about.rateApplication
import io.legado.app.ui.about.shareApplication
import io.legado.app.ui.book.bookmark.AllBookmarksScreen
import io.legado.app.ui.book.cache.CacheDestination
import io.legado.app.ui.book.explore.ExploreShowDestination
import io.legado.app.ui.book.group.BookGroupsDestination
import io.legado.app.ui.book.localimport.LocalBookImportScreen
import io.legado.app.ui.book.remoteimport.RemoteBookImportScreen
import io.legado.app.ui.book.remoteimport.RemoteServersScreen
import io.legado.app.ui.book.remoteimport.RemoteServerEditScreen
import io.legado.app.ui.book.info.BookInfoCatalogReturnDestination
import io.legado.app.ui.book.info.BookInfoEditReturnDestination
import io.legado.app.ui.book.info.BookInfoOpenViewModel
import io.legado.app.ui.book.info.BookInfoOpenEffect
import io.legado.app.ui.book.info.BookInfoDestination
import io.legado.app.ui.book.info.edit.BookInfoEditScreen
import io.legado.app.ui.book.manage.BookshelfManageDestination
import io.legado.app.ui.book.search.SearchScreen
import io.legado.app.data.repository.SearchScopeSpec
import io.legado.app.ui.book.searchContent.SearchContentScreen
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.edit.JsSourceEditScreen
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TextTocRuleScreen
import io.legado.app.ui.book.toc.rule.TextTocRuleImportDialog
import io.legado.app.ui.book.manga.MangaReaderDestination
import io.legado.app.ui.book.read.compose.TextReaderDestination
import io.legado.app.ui.book.toc.ReaderCatalogDestination
import io.legado.app.ui.book.toc.BookTocDestination
import io.legado.app.ui.book.toc.BookTocSelection
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.config.MoreSettingsScreen
import io.legado.app.ui.config.BackupSettingsScreen
import io.legado.app.ui.config.OtherSettingsScreen
import io.legado.app.ui.config.CoverSettingsScreen
import io.legado.app.ui.config.CoverFontSettingsScreen
import io.legado.app.ui.config.ThemeSettingsScreen
import io.legado.app.ui.dict.rule.DictionaryRuleScreen
import io.legado.app.ui.dict.rule.DictionaryRuleImportDialog
import io.legado.app.ui.file.FileManagerScreen
import io.legado.app.ui.main.my.MyAction
import io.legado.app.ui.main.my.MyRootScreen
import io.legado.app.ui.main.explore.ExploreRootScreen
import io.legado.app.ui.main.bookshelf.BookshelfMenuAction
import io.legado.app.ui.main.bookshelf.BookshelfRootScreen
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.AppActivity
import io.legado.app.ui.login.SourceLoginScreen
import io.legado.app.ui.qrcode.QrCodeScreen
import io.legado.app.ui.replace.ReplaceRuleScreen
import io.legado.app.ui.replace.edit.ReplaceRuleEditScreen
import io.legado.app.ui.rulesub.RuleSubScreen
import io.legado.app.ui.highlight.HighlightRuleScreen
import io.legado.app.ui.highlight.edit.HighlightRuleEditorDestination
import io.legado.app.ui.association.BookSourceImportScreen
import io.legado.app.ui.association.FileAssociationScreen
import io.legado.app.data.repository.FileAssociationRequest
import java.util.UUID
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.OpenUrlConfirmScreen
import io.legado.app.ui.association.VerificationCodeScreen
import io.legado.app.ui.association.OnlineImportScreen
import io.legado.app.ui.association.HighlightRuleImportScreen
import io.legado.app.ui.association.HttpTtsImportScreen
import io.legado.app.ui.association.AddToBookshelfScreen
import io.legado.app.ui.replace.ReplaceRuleImportDialog
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.ui.about.openAppLogs

@Composable
fun LegadoApp(
    startRoute: AppRoute = BookshelfRoute,
    initialDestination: AppRoute? = null,
    navigationRequest: AppRoute? = null,
    onNavigationRequestHandled: () -> Unit = {},
    onExit: () -> Unit = {},
    onResult: (Int, Intent?) -> Unit = { _, _ -> },
    fileAssociationInput: Pair<String, FileAssociationRequest>? = null,
    onDestinationChanged: (AppRoute) -> Unit = {},
) {
    val context = LocalContext.current
    val navigationState = rememberAppNavigationState(startRoute, initialDestination)
    val navigator = remember(navigationState) { AppNavigator(navigationState) }
    val navigateBack = {
        if (initialDestination != null && navigationState.currentBackStack().size <= 2) {
            onExit()
        } else if (!navigator.goBack()) {
            onExit()
        }
    }
    LaunchedEffect(navigationState.currentRoute) {
        onDestinationChanged(navigationState.currentRoute as AppRoute)
    }
    LaunchedEffect(navigationRequest) {
        navigationRequest?.let {
            navigator.navigate(it)
            onNavigationRequestHandled()
        }
    }
    val entries = navigationState.toEntries { key ->
        NavEntry(
            key = key,
            metadata = if (key is PhotoViewerRoute) {
                DialogSceneStrategy.dialog(androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                    dismissOnBackPress = false, dismissOnClickOutside = false,
                ))
            } else if (key is OpenUrlConfirmRoute || key is VerificationCodeRoute) {
                DialogSceneStrategy.dialog()
            } else {
                emptyMap()
            },
        ) { route ->
            AppRouteContent(
                route = route as AppRoute,
                onNavigate = { destination -> if (navigationState.currentRoute == route) navigator.navigate(destination) },
                onReplace = { destination -> if (navigationState.currentRoute == route) navigator.replace(destination) },
                fileAssociationInput = fileAssociationInput,
                onMyAction = { action ->
                    when (action) {
                        MyAction.More -> navigator.navigate(SettingsRoute())
                        MyAction.BookSourceManage -> context.startActivity<BookSourceActivity> { putExtra("modernSearch", true) }
                        MyAction.ReplaceRuleManage -> navigator.navigate(ReplaceRuleRoute)
                        MyAction.RuleSubscription -> navigator.navigate(RuleSubscriptionRoute)
                        MyAction.DictionaryRuleManage -> navigator.navigate(DictionaryRuleRoute)
                        MyAction.TextTocRuleManage -> navigator.navigate(TextTocRuleRoute)
                        MyAction.AllBookmarks -> navigator.navigate(AllBookmarksRoute)
                        MyAction.OtherSettings -> navigator.navigate(SettingsRoute(SettingsSection.Other))
                        MyAction.BackupSettings -> navigator.navigate(SettingsRoute(SettingsSection.Backup))
                        MyAction.ThemeSettings -> navigator.navigate(SettingsRoute(SettingsSection.Theme))
                        MyAction.FileManage -> navigator.navigate(FileManagerRoute)
                        MyAction.ReadRecord -> navigator.navigate(ReadRecordRoute)
                        MyAction.About -> navigator.navigate(AboutRoute)
                        MyAction.Exit -> (context as? Activity)?.finish()
                    }
                },
                onThemeModeChanged = { ThemeConfig.applyDayNight(context) },
                onNavigateBack = { if (navigationState.currentRoute == route) navigateBack() },
                isCurrentRoute = navigationState.currentRoute == route,
                onResult = { code, data -> if (navigationState.currentRoute == route) onResult(code, data) },
                detailChildren = navigationState.currentBackStack().mapNotNull { child -> when (child) {
                    is BookTocRoute -> child.bookInfoChildRequestId
                    is EditBookInfoRoute -> child.bookInfoChildRequestId
                    is MangaReaderRoute -> child.requestId.takeIf { child.returnToDetails }
                    is TextReaderRoute -> child.requestId.takeIf { child.returnToDetails }
                    is PlatformTextReaderRoute -> child.requestId
                    is AudioPlayerRoute -> child.requestId.takeIf { child.returnToDetails }
                    is VideoPlayerRoute -> child.requestId.takeIf { child.returnToDetails }
                    is BookInfoRoute -> child.requestId
                    else -> null
                } }.toSet(),
            )
        }
    }

    val immersive = navigationState.currentRoute is BookSourceImportRoute || navigationState.currentRoute is MangaReaderRoute || navigationState.currentRoute is TextReaderRoute || navigationState.currentRoute is BookInfoRoute ||
        (navigationState.currentRoute as? BookTocRoute)?.let {
            it.readerCatalogRequestId != null || it.bookInfoChildRequestId != null
        } == true
    NavigationSuiteScaffold(
        layoutType = if (immersive) NavigationSuiteType.None else
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()),
        navigationSuiteItems = {
            item(
                selected = navigationState.topLevelRoute == BookshelfRoute,
                onClick = { navigator.navigate(BookshelfRoute) },
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                label = { Text(stringResource(R.string.bookshelf)) },
            )
            item(
                selected = navigationState.topLevelRoute == ExploreRoute,
                onClick = { navigator.navigate(ExploreRoute) },
                icon = { Icon(Icons.Default.Explore, contentDescription = null) },
                label = { Text(stringResource(R.string.discovery)) },
            )
            item(
                selected = navigationState.topLevelRoute == MyRoute,
                onClick = { navigator.navigate(MyRoute) },
                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                label = { Text(stringResource(R.string.my)) },
            )
        },
    ) {
        NavDisplay(
            entries = entries,
            onBack = navigateBack,
            modifier = Modifier.fillMaxSize(),
            sceneStrategies = listOf(DialogSceneStrategy()),
        )
    }
}

@Composable
private fun AppRouteContent(
    route: AppRoute,
    onNavigate: (AppRoute) -> Unit,
    onReplace: (AppRoute) -> Unit,
    fileAssociationInput: Pair<String, FileAssociationRequest>?,
    onMyAction: (MyAction) -> Unit,
    onThemeModeChanged: () -> Unit,
    onNavigateBack: () -> Unit,
    onResult: (Int, Intent?) -> Unit,
    detailChildren: Set<String>,
    isCurrentRoute: Boolean,
) {
    val context = LocalContext.current
    val bookOpener = if (route == BookshelfRoute || route is BookshelfManageRoute ||
        route is ExploreBooksRoute || route is AddToBookshelfRoute || route == BookshelfSearchRoute) {
        viewModel<BookInfoOpenViewModel>().also { model ->
            BookInfoOpenEffect(model, isCurrentRoute, detailChildren) { request ->
                val destination = BookInfoRoute(request.bookUrl, request.id, request.previewKey)
                if (route is AddToBookshelfRoute) onReplace(destination) else onNavigate(destination)
            }
        }
    } else null
    if (route is BookInfoRoute) {
        BookInfoDestination(route, onNavigateBack, onNavigate, onResult, childEntries = detailChildren)
        return
    }
    if (route is MangaReaderRoute) {
        MangaReaderDestination(route, onNavigateBack, onNavigate, onResult = { code, data ->
            if (!route.returnToDetails) onResult(code, data)
        }, detailEntries = detailChildren, isCurrent = isCurrentRoute)
        return
    }
    if (route is TextReaderRoute) {
        TextReaderDestination(route, onNavigateBack, onNavigate, isCurrentRoute, detailChildren)
        return
    }
    if (route is PlatformTextReaderRoute) {
        io.legado.app.ui.book.read.PlatformTextReaderDestination(route, onNavigateBack)
        return
    }
    if (route is AudioPlayerRoute) {
        io.legado.app.ui.book.read.PlatformReaderDestination(route.bookUrl,
            route.requestId.takeIf { route.returnToDetails }, io.legado.app.ui.book.audio.AudioPlayActivity::class.java, onNavigateBack,
            requireRequest = route.returnToDetails)
        return
    }
    if (route is VideoPlayerRoute) {
        io.legado.app.ui.book.read.PlatformReaderDestination(route.bookUrl,
            route.requestId.takeIf { route.returnToDetails }, io.legado.app.ui.video.VideoPlayerActivity::class.java, onNavigateBack,
            requireRequest = route.returnToDetails, chapterIndex = route.chapterIndex)
        return
    }
    if (route == BookshelfSearchRoute) {
        io.legado.app.ui.main.bookshelf.BookshelfSearchScreen(onBack = onNavigateBack, onOpenBook = { bookOpener!!.openShelf(it) })
        return
    }
    if (route is SearchRoute) {
        SearchScreen(route, onNavigateBack,
            onOpenBook = { request -> onNavigate(BookInfoRoute(request.bookUrl, request.id, request.previewKey)) },
            isCurrent = isCurrentRoute, knownDetails = detailChildren,
            onManageSources = { context.startActivity<BookSourceActivity> { putExtra("modernSearch", true) } },
            onLogs = { onNavigate(AppLogRoute) })
        return
    }
    if (route is RemoteBookRoute) {
        RemoteBookImportScreen(onBack = onNavigateBack, onOpenBook = context::startActivityForBook,
            onServers = { onNavigate(RemoteServersRoute) }, onLogs = { onNavigate(AppLogRoute) })
        return
    }
    if (route is RemoteServersRoute) {
        RemoteServersScreen(onBack = onNavigateBack, onEdit = { onNavigate(RemoteServerEditRoute(it)) })
        return
    }
    if (route is RemoteServerEditRoute) {
        RemoteServerEditScreen(serverId = route.serverId, onBack = onNavigateBack)
        return
    }
    if (route is ImportLocalBookRoute) {
        LocalBookImportScreen(onBack = onNavigateBack, onOpenBook = context::startActivityForBook)
        return
    }
    if (route is FileAssociationRoute) {
        FileAssociationScreen(
            route = route,
            initialRequest = fileAssociationInput?.takeIf { it.first == route.requestId }?.second,
            onReplace = onReplace,
            onOpenBook = context::startActivityForBook,
            onDismiss = onNavigateBack,
        )
        return
    }
    if (route is AddToBookshelfRoute) {
        AddToBookshelfScreen(
            bookUrl = route.bookUrl,
            onBookLoaded = { book -> bookOpener!!.openSelected(book) },
            onDismiss = onNavigateBack,
        )
        return
    }
    if (route is BookSourceImportRoute) {
        BookSourceImportScreen(
            route = route,
            onNavigate = onNavigate,
            onDismiss = onNavigateBack,
        )
        return
    }
    if (route is ImportRoute) {
        when (route.kind) {
            ImportKind.HighlightRule -> HighlightRuleImportScreen(
                source = route.payload,
                requestId = route.requestId,
                parentManagerId = route.parentManagerId,
                onDismiss = onNavigateBack,
            )
            ImportKind.ReplaceRule -> ReplaceRuleImportDialog(
                source = route.payload,
                onDismiss = onNavigateBack,
            )
            ImportKind.DictionaryRule -> DictionaryRuleImportDialog(
                source = route.payload,
                onDismiss = onNavigateBack,
            )
            ImportKind.TextTocRule -> TextTocRuleImportDialog(
                source = route.payload,
                onDismiss = onNavigateBack,
            )
            ImportKind.HttpTts -> HttpTtsImportScreen(
                source = route.payload,
                onDismiss = onNavigateBack,
            )
            else -> Unit
        }
        return
    }
    if (route == AboutRoute) {
        val activity = context as AppCompatActivity
        AboutScreen(
            onBack = onNavigateBack,
            onShare = activity::shareApplication,
            onRate = activity::rateApplication,
            onAction = { performAboutAction(activity, it) },
        )
        return
    }
    if (route == AppLogRoute) {
        AppLogScreen(onBack = onNavigateBack)
        return
    }
    if (route is SettingsRoute && route.section == SettingsSection.Backup) {
        BackupSettingsScreen(
            onBack = onNavigateBack,
            onOpenLog = { onNavigate(AppLogRoute) },
        )
        return
    }
    if (route == AllBookmarksRoute) {
        AllBookmarksScreen(
            onBack = onNavigateBack,
            onOpenBook = { request ->
                context.startActivityForBook(request.book) {
                    putExtra("index", request.chapterIndex)
                    putExtra("chapterPos", request.chapterPos)
                }
            },
        )
        return
    }
    if (route is BookTocRoute) {
        if (route.bookInfoChildRequestId != null) {
            BookInfoCatalogReturnDestination(route.bookUrl, route.bookInfoChildRequestId, onNavigateBack,
                onOpenLogs = { onNavigate(AppLogRoute) }, onOpenRuleManager = { onNavigate(TextTocRuleRoute) })
            return
        }
        if (route.readerCatalogRequestId != null) {
            ReaderCatalogDestination(route.bookUrl, route.readerCatalogRequestId, onNavigateBack,
                onOpenLogs = { onNavigate(AppLogRoute) }, onOpenRuleManager = { onNavigate(TextTocRuleRoute) })
            return
        }
        BookTocDestination(
            bookUrl = route.bookUrl,
            openTxtTocRules = route.openTxtTocRules,
            onBack = onNavigateBack,
            onOpenLogs = { onNavigate(AppLogRoute) },
            onOpenRuleManager = { onNavigate(TextTocRuleRoute) },
            onSelect = { selection ->
                onResult(Activity.RESULT_OK, selection.toTocResultIntent())
                onNavigateBack()
            },
        )
        return
    }
    if (route is SettingsRoute && route.section == SettingsSection.Root) {
        MoreSettingsScreen(
            onBack = onNavigateBack,
            onAction = onMyAction,
            onThemeModeChanged = onThemeModeChanged,
        )
        return
    }
    if (route is SettingsRoute && route.section == SettingsSection.Other) {
        OtherSettingsScreen(onBack = onNavigateBack)
        return
    }
    if (route is SettingsRoute && route.section == SettingsSection.Theme) {
        ThemeSettingsScreen(
            onBack = onNavigateBack,
            onOpenCoverSettings = { onNavigate(SettingsRoute(SettingsSection.Cover)) },
        )
        return
    }
    if (route is SettingsRoute && route.section == SettingsSection.Cover) {
        CoverSettingsScreen(
            onBack = onNavigateBack,
            onOpenFontSettings = { onNavigate(SettingsRoute(SettingsSection.CoverFont)) },
        )
        return
    }
    if (route is SettingsRoute && route.section == SettingsSection.CoverFont) {
        CoverFontSettingsScreen(onBack = onNavigateBack)
        return
    }
    if (route == FileManagerRoute) {
        FileManagerScreen(onExit = onNavigateBack)
        return
    }
    if (route == ReadRecordRoute) {
        ReadRecordScreen(
            onBack = onNavigateBack,
            onOpenDestination = { destination ->
                when (destination) {
                    is ReadRecordDestination.OpenBook -> context.startActivityForBook(destination.book)
                    is ReadRecordDestination.SearchBook -> onNavigate(SearchRoute(destination.query))
                }
            },
        )
        return
    }
    if (route == TextTocRuleRoute) {
        val activity = context as AppCompatActivity
        TextTocRuleScreen(
            onBack = onNavigateBack,
            onShareFile = activity::share,
        )
        return
    }
    if (route == DictionaryRuleRoute) {
        val activity = context as AppCompatActivity
        DictionaryRuleScreen(
            onBack = onNavigateBack,
            onShareFile = activity::share,
        )
        return
    }
    if (route is ReplaceRuleEditRoute) {
        ReplaceRuleEditScreen(
            ruleId = route.ruleId,
            pattern = route.pattern,
            isRegex = route.isRegex,
            scope = route.scope,
            onBack = onNavigateBack,
            onSaved = {
                onResult(Activity.RESULT_OK, null)
                onNavigateBack()
            },
        )
        return
    }
    if (route == ReplaceRuleRoute) {
        val activity = context as AppCompatActivity
        ReplaceRuleScreen(
            onBack = onNavigateBack,
            onEdit = { id -> onNavigate(ReplaceRuleEditRoute(ruleId = id)) },
            onShareFile = activity::share,
            onExit = { Coroutine.async { ContentProcessor.upReplaceRules() } },
        )
        return
    }
    if (route == RuleSubscriptionRoute) {
        val activity = context as AppCompatActivity
        RuleSubScreen(
            onBack = onNavigateBack,
            onOpenBookSourceSubscription = {
                onNavigate(BookSourceImportRoute(it))
            },
            onOpenReplaceRuleSubscription = {
                activity.showDialogFragment(ImportReplaceRuleDialog(it))
            },
        )
        return
    }
    if (route is HighlightRuleEditRoute) {
        HighlightRuleEditorDestination(route,onNavigateBack)
        return
    }
    if (route is CacheRoute) {
        CacheDestination(route,onNavigateBack,onLogs={ onNavigate(AppLogRoute) },
            onWebDavSetup={ onNavigate(SettingsRoute(SettingsSection.Backup)) })
        return
    }
    if (route == HighlightRuleRoute) {
        HighlightRuleScreen(
            onBack = onNavigateBack,
            onImport = { requestId, source, parent -> onNavigate(ImportRoute(source, ImportKind.HighlightRule, requestId, parent)) },
            onEdit = { id -> onNavigate(HighlightRuleEditRoute(id)) },
        )
        return
    }
    if (route is OpenUrlConfirmRoute) {
        OpenUrlConfirmScreen(
            route = route,
            onDismiss = onNavigateBack,
        )
        return
    }
    if (route is VerificationCodeRoute) {
        VerificationCodeScreen(
            route = route,
            onDismiss = onNavigateBack,
        )
        return
    }
    if (route is PhotoViewerRoute) {
        io.legado.app.ui.photo.PhotoViewerScreen(route.requestKey, onNavigateBack)
        return
    }
    if (route is EditBookInfoRoute) {
        if (route.bookInfoChildRequestId != null) {
            BookInfoEditReturnDestination(route.bookUrl, route.bookInfoChildRequestId, onNavigateBack)
            return
        }
        BookInfoEditScreen(
            bookUrl = route.bookUrl,
            onBack = onNavigateBack,
            onSaved = {
                onResult(Activity.RESULT_OK, null)
                onNavigateBack()
            },
        )
        return
    }
    if (route is SearchContentRoute) {
        SearchContentScreen(
            route = route,
            onBack = onNavigateBack,
            onResult = { data ->
                onResult(Activity.RESULT_OK, data)
                onNavigateBack()
            },
        )
        return
    }
    if (route is JavaScriptSourceEditRoute) {
        JsSourceEditScreen(
            sourceUrl = route.sourceUrl,
            onBack = onNavigateBack,
            onResult = onResult,
        )
        return
    }
    if (route == QrCodeRoute) {
        QrCodeScreen(
            onBack = onNavigateBack,
            onResult = onResult,
        )
        return
    }
    if (route is SourceLoginRoute) {
        SourceLoginScreen(
            route = route,
            onBack = onNavigateBack,
        )
        return
    }
    if (route is OnlineImportRoute) {
        OnlineImportScreen(
            route = route,
            onExit = onNavigateBack,
            onOpenFile = { onReplace(FileAssociationRoute(UUID.randomUUID().toString(), it)) },
            onImport = { type, source ->
                if (type == "addToBookshelf") {
                    onReplace(AddToBookshelfRoute(source))
                    return@OnlineImportScreen
                }
                if (type == "bookSource") {
                    onReplace(BookSourceImportRoute(source))
                    return@OnlineImportScreen
                }
                val kind = when (type) {
                    "txtRule" -> ImportKind.TextTocRule
                    "dictRule" -> ImportKind.DictionaryRule
                    "httpTts" -> ImportKind.HttpTts
                    "highlightRule" -> ImportKind.HighlightRule
                    "replaceRule" -> ImportKind.ReplaceRule
                    else -> null
                }
                kind?.let { onReplace(ImportRoute(source, it)) }
            },
        )
        return
    }
    if (route is ExploreBooksRoute) {
        ExploreShowDestination(
            route = route,
            onBack = onNavigateBack,
            onOpenBook = { book -> bookOpener!!.openSelected(book.toBook()) },
        )
        return
    }
    if (route == BookshelfRoute) {
        val mainViewModel: MainViewModel = viewModel()
        BookshelfRootScreen(
            isCurrent = isCurrentRoute,
            onMenuAction = { action, state ->
                when (action) {
                    BookshelfMenuAction.SEARCH -> onNavigate(BookshelfSearchRoute)
                    BookshelfMenuAction.FIND_BOOKS -> onNavigate(SearchRoute())
                    BookshelfMenuAction.UPDATE_TOC -> mainViewModel.upToc(
                        state.books,
                        state.currentGroup?.onlyUpdateRead ?: false,
                    )
                    BookshelfMenuAction.ADD_LOCAL -> onNavigate(ImportLocalBookRoute)
                    BookshelfMenuAction.ADD_REMOTE -> onNavigate(RemoteBookRoute)
                    BookshelfMenuAction.BOOKSHELF_MANAGE -> onNavigate(BookshelfManageRoute(state.groupId))
                    BookshelfMenuAction.CACHE_EXPORT -> onNavigate(CacheRoute(state.groupId))
                    BookshelfMenuAction.GROUP_MANAGE -> onNavigate(BookGroupsRoute)
                    BookshelfMenuAction.LOG -> context.openAppLogs()
                    else -> Unit
                }
            },
            onBookClick = context::startActivityForBook,
            onBookLongClick = { book -> bookOpener!!.openShelf(book.bookUrl) },
            onGroupLongClick = { group -> onNavigate(BookshelfManageRoute(group.groupId)) },
        )
        return
    }
    if (route == BookGroupsRoute) {
        BookGroupsDestination(onNavigateBack)
        return
    }
    if (route is BookshelfManageRoute) {
        BookshelfManageDestination(route.groupId, onNavigateBack,
            onManageGroups = { onNavigate(BookGroupsRoute) }, onOpenBookInfo = { bookOpener!!.openShelf(it) })
        return
    }
    if (route == ExploreRoute) {
        ExploreRootScreen(
            onSearchBooks = { onNavigate(SearchRoute()) },
            onManageSources = { context.startActivity<BookSourceActivity> { putExtra("modernSearch", true) } },
            onEditSource = { sourceUrl ->
                context.startActivity<BookSourceEditActivity> { putExtra("sourceUrl", sourceUrl) }
            },
            onLoginSource = { sourceUrl ->
                context.startActivity(
                    AppActivity.startIntent(
                        context,
                        SourceLoginRoute(SourceLoginTarget.BookSource, key = sourceUrl),
                    ),
                )
            },
            onSearchSource = { source -> onNavigate(SearchRoute(scope = SearchScopeSpec.source(source.bookSourceName, source.bookSourceUrl).raw)) },
            onOpenExplore = { sourceUrl, title, exploreUrl ->
                onNavigate(ExploreBooksRoute(sourceUrl, title, exploreUrl))
            },
        )
        return
    }
    if (route == MyRoute) {
        MyRootScreen(
            onAction = onMyAction,
            onThemeModeChanged = onThemeModeChanged,
        )
        return
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = route.routeLabel(),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

private fun BookTocSelection.toTocResultIntent(): Intent = Intent().apply {
    putExtra("index", chapterIndex)
    putExtra("chapterPos", chapterPosition)
    putExtra("chapterChanged", chapterChanged)
    volumeIndex?.let { putExtra("durVolumeIndex", it) }
    chapterInVolumeIndex?.let { putExtra("chapterInVolumeIndex", it) }
    highlightLayoutTitleLength?.let {
        putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, it)
    }
    highlightAnchorText?.let { putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, it) }
    pdfPageIndex?.let { putExtra(TocActivityResult.EXTRA_PDF_PAGE_INDEX, it) }
}

private fun AppRoute.routeLabel(): String = when (this) {
    BookshelfRoute -> "书架"
    ExploreRoute -> "发现"
    MyRoute -> "我的"
    else -> this::class.simpleName.orEmpty()
}
