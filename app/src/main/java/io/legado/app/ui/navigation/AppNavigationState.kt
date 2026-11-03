package io.legado.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.ViewModelStoreOwner

/** Navigation state for independent top-level stacks with process-death restoration. */
@Composable
fun rememberAppNavigationState(
    startRoute: AppRoute = BookshelfRoute,
    initialDestination: AppRoute? = null,
    topLevelRoutes: Set<AppRoute> = TopLevelRoutes,
): AppNavigationState {
    require(startRoute in topLevelRoutes) { "The start route must be a top-level route" }
    require(initialDestination !in topLevelRoutes) { "The initial destination must not be a top-level route" }

    val topLevelRoute = rememberSerializable(
        startRoute,
        topLevelRoutes,
        serializer = MutableStateSerializer(NavKeySerializer()),
    ) {
        mutableStateOf<NavKey>(startRoute)
    }
    val backStacks = topLevelRoutes.associateWith { route ->
        if (route == startRoute && initialDestination != null) {
            rememberNavBackStack(route, initialDestination)
        } else {
            rememberNavBackStack(route)
        }
    }

    return remember(startRoute, topLevelRoutes) {
        AppNavigationState(
            startRoute = startRoute,
            topLevelRoute = topLevelRoute,
            backStacks = backStacks,
        )
    }
}

class AppNavigationState internal constructor(
    val startRoute: AppRoute,
    topLevelRoute: MutableState<NavKey>,
    val backStacks: Map<AppRoute, NavBackStack<NavKey>>,
) {
    var topLevelRoute: NavKey by topLevelRoute

    val stacksInUse: List<AppRoute>
        get() = if (topLevelRoute == startRoute) {
            listOf(startRoute)
        } else {
            listOf(startRoute, topLevelRoute as AppRoute)
        }

    val currentRoute: NavKey
        get() = currentBackStack().last()

    internal fun currentBackStack(): NavBackStack<NavKey> =
        backStacks[topLevelRoute] ?: error("No back stack registered for $topLevelRoute")
}

class AppNavigator(private val state: AppNavigationState) {
    fun replace(route: AppRoute) {
        val stack = state.currentBackStack()
        require(stack.last() !in state.backStacks)
        stack.removeLastOrNull()
        stack.add(route)
    }

    fun navigate(route: AppRoute) {
        if (route in state.backStacks) {
            state.topLevelRoute = route
        } else {
            state.currentBackStack().add(route)
        }
    }

    fun goBack(): Boolean {
        val currentStack = state.currentBackStack()
        return if (currentStack.last() == state.topLevelRoute) {
            if (state.topLevelRoute == state.startRoute) {
                false
            } else {
                state.topLevelRoute = state.startRoute
                true
            }
        } else {
            currentStack.removeLastOrNull()
            true
        }
    }
}

@Composable
fun AppNavigationState.toEntries(
    entryProvider: (NavKey) -> NavEntry<NavKey>,
): SnapshotStateList<NavEntry<NavKey>> {
    val parentOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val decoratedEntries = backStacks.mapValues { (root, stack) ->
        val viewModelStores = remember(parentOwner, root) {
            navigationViewModelStores(parentOwner, root)
        }
        rememberDecoratedNavEntries(
            backStack = stack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(viewModelStores),
            ),
            entryProvider = entryProvider,
        )
    }
    return stacksInUse
        .flatMap { decoratedEntries[it].orEmpty() }
        .toMutableStateList()
}

/** A separate retained store per top-level stack; entry stores are cleared on pop. */
fun navigationViewModelStores(owner: ViewModelStoreOwner, root: NavKey): ViewModelStoreProvider =
    ViewModelStoreProvider(owner, "app-navigation:$root")
