package com.infowings.catalog.reference.book

import com.infowings.catalog.aspects.getAllAspects
import com.infowings.catalog.aspects.sort.aspectSort
import com.infowings.catalog.common.*
import com.infowings.catalog.common.BadRequest
import com.infowings.catalog.common.ReferenceBook
import com.infowings.catalog.common.ReferenceBookItem
import com.infowings.catalog.utils.BadRequestException
import kotlinx.coroutines.experimental.launch
import kotlinx.serialization.json.JSON
import react.*
import kotlin.reflect.KClass

class RefBookBadRequestException(val exceptionInfo: BadRequest) : RuntimeException(exceptionInfo.message)

interface ReferenceBookApiReceiverProps : RProps {
    var rowDataList: List<RowData>
    var createBook: suspend (ReferenceBook) -> Unit
    var updateBook: suspend (ReferenceBook) -> Unit
    var deleteBook: suspend (ReferenceBook, force: Boolean) -> Unit
    var createBookItem: suspend (ReferenceBookItem) -> Unit
    var updateBookItem: suspend (ReferenceBookItem, force: Boolean) -> Unit
    var deleteBookItem: suspend (ReferenceBookItem, force: Boolean) -> Unit
}


/**
 * Component that manages already fetched books and makes real requests to the server API
 */
class ReferenceBookApiMiddleware : RComponent<ReferenceBookApiMiddleware.Props, ReferenceBookApiMiddleware.State>() {

    override fun State.init() {
        rowDataList = emptyList()
    }

    override fun componentDidMount() {
        fetchData(listOf(AspectOrderBy(AspectSortField.NAME, Direction.ASC)))
    }

    private fun fetchData(orderBy: List<AspectOrderBy> = emptyList()) {
        launch {
            val aspectIdToBookMap = getAllReferenceBooks().books
                .filterNot { it.deleted }
                .map { Pair(it.aspectId, it) }
                .toMap()

            val rowDataList = getAllAspects(orderBy).aspects
                .filterNot { it.deleted }
                .filter {it.baseType == BaseType.Text.name}
                .map {
                    val aspectId = it.id ?: ""
                    val book = if (it.id != null) aspectIdToBookMap[aspectId] else null
                    RowData(aspectId, it.name ?: "", book)
                }

            setState {
                this.rowDataList = rowDataList
            }
        }
    }

    private suspend fun handleCreateBook(book: ReferenceBook) {
        /*
        Maybe get ReferenceBook is not optimal way.
        Actually we need only created ReferenceBook id.
        */
        val newBook = createReferenceBook(book)
        updateRowDataList(book.aspectId, newBook)
    }

    private suspend fun handleUpdateBook(book: ReferenceBook) {
        /*
        Maybe get ReferenceBook with all his children is not optimal way, because it can be very large json
        Actually we need only to know is updating was successful.
        */
        updateReferenceBook(book)
        val updatedBook = getReferenceBook(book.aspectId)
        updateRowDataList(book.aspectId, updatedBook)
    }

    private suspend fun handleDeleteBook(book: ReferenceBook, force: Boolean) {
        /*
        Maybe get ReferenceBook with all his children is not optimal way, because it can be very large json
        Actually we need only to know is updating was successful.
        */
        try {
            if (force) {
                forceDeleteReferenceBook(book)
            } else {
                deleteReferenceBook(book)
            }
            updateRowDataList(book.aspectId, null)
        } catch (e: BadRequestException) {
            throw RefBookBadRequestException(JSON.parse(e.message))
        }
    }

    private suspend fun handleCreateBookItem(bookItem: ReferenceBookItem) {
        /*
        Maybe get ReferenceBook with all his children is not optimal way, because it can be very large json
        Actually we need only created ReferenceBookItem id.
        */
        createReferenceBookItem(bookItem)
        val updatedBook = getReferenceBook(bookItem.aspectId)
        updateRowDataList(updatedBook.aspectId, updatedBook)
    }

    private suspend fun handleUpdateBookItem(bookItem: ReferenceBookItem, force: Boolean) {
        /*
        Maybe get ReferenceBook with all his children is not optimal way, because it can be very large json
        Actually we need only to know is updating was successful.
        */
        try {
            if (force) {
                forceUpdateReferenceBookItem(bookItem)
            } else {
                updateReferenceBookItem(bookItem)
            }
            val updatedBook = getReferenceBook(bookItem.aspectId)
            updateRowDataList(updatedBook.aspectId, updatedBook)
        } catch (e: BadRequestException) {
            throw RefBookBadRequestException(JSON.parse(e.message))
        }
    }

    private suspend fun handleDeleteBookItem(bookItem: ReferenceBookItem, force: Boolean) {
        /*
        Maybe get ReferenceBook with all his children is not optimal way, because it can be very large json
        Actually we need only to know is updating was successful.
        */
        try {
            if (force) {
                forceDeleteReferenceBookItem(bookItem)
            } else {
                deleteReferenceBookItem(bookItem)
            }
            val updatedBook = getReferenceBook(bookItem.aspectId)
            updateRowDataList(updatedBook.aspectId, updatedBook)
        } catch (e: BadRequestException) {
            throw RefBookBadRequestException(JSON.parse(e.message))
        }
    }

    private fun updateRowDataList(aspectId: String, book: ReferenceBook?) {
        setState {
            rowDataList = rowDataList.map {
                if (it.aspectId == aspectId) it.copy(book = book) else it
            }
        }
    }


    override fun RBuilder.render() {
        aspectSort {
            attrs {
                onFetchAspect = ::fetchData
            }
        }
        child(props.apiReceiverComponent) {
            attrs {
                rowDataList = state.rowDataList
                createBook = { handleCreateBook(it) }
                updateBook = { handleUpdateBook(it) }
                deleteBook = { book, force -> handleDeleteBook(book, force) }
                createBookItem = { handleCreateBookItem(it) }
                updateBookItem = { bookItem, force -> handleUpdateBookItem(bookItem, force) }
                deleteBookItem = { bookItem, force -> handleDeleteBookItem(bookItem, force) }
            }
        }
    }

    interface Props : RProps {
        var apiReceiverComponent: KClass<out RComponent<ReferenceBookApiReceiverProps, *>>
    }

    interface State : RState {
        var rowDataList: List<RowData>
    }
}

fun RBuilder.referenceBookApiMiddleware(apiReceiverComponent: KClass<out RComponent<ReferenceBookApiReceiverProps, *>>) =
    child(ReferenceBookApiMiddleware::class) {
        attrs {
            this.apiReceiverComponent = apiReceiverComponent
        }
    }

data class RowData(val aspectId: String, val aspectName: String, val book: ReferenceBook?)