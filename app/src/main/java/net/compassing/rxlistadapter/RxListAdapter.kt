package net.compassing.rxlistadapter


import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jakewharton.rxbinding2.view.RxView
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import timber.log.Timber


/**
 * (Note: I haven't yet released this to Github/JCenter, but intend to open-source it soon).
 *
 * A base class aiding the development of MVI-like [ListAdapter]s.
 *
 * The adapter is expected to display a stream of ViewModels of type [VM] which are
 * bound into 1 or more subtypes of ViewHolder [VH], each of which can emit events of type [E]
 * into the [eventRelay].
 */
abstract class RxListAdapter<VM : Any, E, VT : Enum<VT>, VH : RxViewHolder<VM, E>>(
        diffCallback: DiffUtil.ItemCallback<VM>
) : ListAdapter<VM, VH>(diffCallback) {

    //region Public Interface

    /**
     * Downstream consumers should subscribe to this to process events from the items.
     */
    fun getObservable(): Observable<E> = eventRelay.hide()

    //endregion

    //region Implementation Required

    /**
     * Given a [viewModel], returns the corresponding ViewType [VT].
     */
    abstract fun getItemViewType(viewModel: VM): VT

    /**
     * This is required to map the [viewType] passed in by the [ListAdapter] into a [VT] enum
     */
    abstract fun getViewTypeFromInt(viewType: Int): VT

    /**
     * Implementors map the [viewType] into an [RxViewHolder].
     */
    abstract fun onCreateViewHolder(parent: ViewGroup, viewType: VT): VH

    //endregion

    //region Interface: ListAdapter<T, VH>

    override fun getItemViewType(position: Int): Int {
        return getItemViewType(getItem(position)).ordinal
    }

    /**
     * Map the [viewType] into a [VT] enum and call the provided [onCreateViewHolder].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            onCreateViewHolder(parent, getViewTypeFromInt(viewType))

    /**
     * Binds the [VM] at [position] to the [viewHolder].
     */
    override fun onBindViewHolder(viewHolder: VH, position: Int) {
        viewHolder.bindModelAndEvents(getItem(position))
    }

    /**
     * Cut the cord whenever a [viewHolder] is recycled.
     */
    override fun onViewRecycled(viewHolder: VH) {
        viewHolder.unsubscribeEvents()
    }

    //endregion

    //region Private Fields

    /**
     * Each field can optionally bindModelAndEvents a stream of events which are pushed through this relay.
     * These events are exposed by [getObservable()]
     */
    protected val eventRelay: PublishRelay<E> = PublishRelay.create()

    //endregion

}

/**
 * Base class for all [ViewHolder]s used with an [RxListAdapter].
 *
 * Each subclass is responsible for rendering models of type [M] and emitting events of type [E].
 *
 * Implementors must provide:
 * - a [bindUI] method to update the view to represent the new model
 * - an [eventStream] method to supply the [Observable] of events
 *
 * [RxViewHolder] will manage the disposables from the event stream subscription and automatically
 * clear them when recycled or re-bound.
 */
abstract class RxViewHolder<M : Any, E>(
        private val eventRelay: PublishRelay<E>,
        private val parent: ViewGroup,
        protected val itemView: View
) : RecyclerView.ViewHolder(itemView) {

    //region Member Fields

    private val disposables = CompositeDisposable()
    protected lateinit var viewModel: M

    //endregion

    //region Core Implementation

    /** Responsible for setting the [viewModel] and subscribing to the [eventStream] */
    fun bindModelAndEvents(newModel: M) {
        viewModel = newModel
        bindUI()

        unsubscribeEvents()
        eventStream()
                .takeUntil(RxView.detaches(parent))
                .subscribe(this::handleEvent, this::handleError)
                .addTo(disposables)
    }

    /** Removes all active subscriptions in preparation for recycling */
    fun unsubscribeEvents() {
        disposables.clear()
    }

    //endregion

    //region Implementation Required

    /** Implementors provide this to modify the [itemView] to represent the [viewModel] */
    abstract fun bindUI()

    /**
     * Implementors provide this using e.g. RxBinding to convert UI interactions into a
     * stream of Events [E]
     */
    abstract fun eventStream(): Observable<E>

    //endregion

    //region Overridable Methods

    /** Passes the [event] along to consumers via [eventRelay] */
    @CallSuper
    open fun handleEvent(event: E) {
        eventRelay.accept(event)
    }

    /**
     * Base implementation; highly recommend implementors override. This should only be for
     * unexpected errors, application-level errors should be expressed in terms of an Event [E].
     */
    open fun handleError(e: Throwable) {
        Timber.e(e, "Error in RxViewHolder event stream")
    }

    //endregion

}
