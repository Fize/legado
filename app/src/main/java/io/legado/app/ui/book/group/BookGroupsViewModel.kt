package io.legado.app.ui.book.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.repository.BookGroupRepository
import javax.inject.Inject

@HiltViewModel
class BookGroupsViewModel @Inject constructor(saved: SavedStateHandle, repository: BookGroupRepository) : ViewModel() {
    val controller = BookGroupsController(saved, viewModelScope, repository)
    init { if (!controller.state.value.open) controller.openManage() }
}
