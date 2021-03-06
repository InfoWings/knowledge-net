package com.infowings.catalog.objects.edit.tree.inputs

import com.infowings.catalog.common.RefBookNodeDescriptor
import com.infowings.catalog.objects.edit.tree.inputs.dialog.selectReferenceBookValueDialog
import com.infowings.catalog.reference.book.getReferenceBookItemPath
import com.infowings.catalog.utils.JobCoroutineScope
import com.infowings.catalog.utils.JobSimpleCoroutineScope
import com.infowings.catalog.wrappers.blueprint.Button
import com.infowings.catalog.wrappers.blueprint.Intent
import com.infowings.catalog.wrappers.react.asReactElement
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import react.*

class RefBookValue(val refBookId: String, val refBookTreePath: List<RefBookNodeDescriptor>)

class ReferenceBookInput(props: ReferenceBookInput.Props) :
    RComponent<ReferenceBookInput.Props, ReferenceBookInput.State>(props),
    JobCoroutineScope by JobSimpleCoroutineScope() {

    override fun State.init(props: Props) {
        isDialogOpen = false
        value = RefBookValue(props.refBookId, listOf())
    }

    override fun componentWillUnmount() {
        job.cancel()
    }

    override fun componentDidMount() {
        job = Job()
        props.itemId?.let { itemId ->
            if (itemId.isNotBlank()) {
                launch {
                    val itemPath: List<RefBookNodeDescriptor> = getReferenceBookItemPath(itemId).path
                    setState {
                        value = RefBookValue(value.refBookId, itemPath)
                    }
                }
            }
        }
    }

    private fun handleClick() = setState {
        isDialogOpen = true
    }

    private fun handleCloseDialog() = setState {
        isDialogOpen = false
    }

    private fun handleConfirmSelectNewValue(newValue: RefBookValue) {
        props.onUpdate(newValue.refBookTreePath.last().id)
        setState {
            isDialogOpen = false
            value = newValue
        }
    }

    override fun RBuilder.render() {
        if (state.value.refBookTreePath.isEmpty()) {
            emptyReferenceBookInput(
                onClick = this@ReferenceBookInput::handleClick,
                disabled = props.disabled ?: false
            )
        } else {
            valueReferenceBookInput(
                renderedPath = state.value.refBookTreePath,
                onClick = this@ReferenceBookInput::handleClick,
                disabled = props.disabled ?: false
            )
        }
        selectReferenceBookValueDialog(
            isOpen = state.isDialogOpen,
            initialValue = state.value,
            onSelect = this@ReferenceBookInput::handleConfirmSelectNewValue,
            onCancel = this@ReferenceBookInput::handleCloseDialog
        )
    }

    interface Props : RProps {
        var itemId: String?
        var refBookId: String
        var onUpdate: (String) -> Unit
        var disabled: Boolean?
    }

    interface State : RState {
        var isDialogOpen: Boolean
        var value: RefBookValue
    }
}

fun RBuilder.emptyReferenceBookInput(onClick: () -> Unit, disabled: Boolean) = Button {
    attrs {
        text = "Select value".asReactElement()
        intent = Intent.NONE
        this.onClick = { onClick() }
        this.disabled = disabled
    }
}

fun RBuilder.valueReferenceBookInput(renderedPath: List<RefBookNodeDescriptor>, onClick: () -> Unit, disabled: Boolean) = Button {
    attrs {
        text = renderedPath.joinToString(" → ") { it.value }.asReactElement()
        intent = Intent.NONE
        this.onClick = { onClick() }
        this.disabled = disabled
    }
}

fun RBuilder.referenceBookInput(handler: RHandler<ReferenceBookInput.Props>) = child(ReferenceBookInput::class, handler)