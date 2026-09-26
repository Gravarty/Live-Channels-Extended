package com.android.tv.search

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.SearchBar
import androidx.lifecycle.lifecycleScope
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.TvSingletons
import com.android.tv.util.images.ImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Suche in der Programmübersicht (Kanäle/Sendungen), als Leanback-SearchSupportFragment.
 * Suche läuft per Coroutine statt AsyncTask.
 */
class ProgramGuideSearchFragment : SearchSupportFragment() {

    private val presenter = object : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            if (DEBUG) Log.d(TAG, "onCreateViewHolder")
            val cardView = ImageCardView(mainActivity).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setMainImageAdjustViewBounds(false)
            }
            val res = mainActivity.resources
            cardView.setMainImageDimensions(
                res.getDimensionPixelSize(R.dimen.card_image_layout_width),
                res.getDimensionPixelSize(R.dimen.card_image_layout_height),
            )
            return ViewHolder(cardView)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val cardView = viewHolder.view as ImageCardView
            val result = item as LocalSearchProvider.SearchResult
            if (DEBUG) Log.d(TAG, "onBindViewHolder result:$result")
            cardView.titleText = result.title
            if (!TextUtils.isEmpty(result.imageUri)) {
                ImageLoader.loadBitmap(mainActivity, result.imageUri, mainCardWidth, mainCardHeight, createImageLoaderCallback(cardView))
            } else {
                cardView.mainImage = mainActivity.getDrawable(R.drawable.ic_tv_app_96x96)
            }
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            // Nichts zu tun
        }
    }

    private val searchResultProvider = object : SearchResultProvider {
        override fun getResultsAdapter(): ObjectAdapter = resultAdapter

        override fun onQueryTextChange(newQuery: String): Boolean {
            searchAndRefresh(newQuery)
            return true
        }

        override fun onQueryTextSubmit(query: String): Boolean {
            searchAndRefresh(query)
            return true
        }
    }

    private val itemClickedListener = OnItemViewClickedListener { _, item, _, _ ->
        val result = item as LocalSearchProvider.SearchResult
        mainActivity.supportFragmentManager.popBackStack()
        mainActivity.tuneToChannel(mainActivity.channelDataManager.getChannel(result.channelId))
    }

    private val resultAdapter = ArrayObjectAdapter(ListRowPresenter())
    private lateinit var mainActivity: MainActivity
    private lateinit var search: SearchInterface
    private var mainCardWidth = 0
    private var mainCardHeight = 0
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainActivity = requireActivity() as MainActivity
        // Die TvProvider-Suche des Originals (nur mit ACCESS_ALL_EPG_DATA) entfällt wie in
        // LocalSearchProvider; es wird immer in den geladenen Daten gesucht.
        search = DataManagerSearch(mainActivity)
        val res = resources
        mainCardWidth = res.getDimensionPixelSize(R.dimen.card_image_layout_width)
        mainCardHeight = res.getDimensionPixelSize(R.dimen.card_image_layout_height)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        v?.setBackgroundResource(R.color.program_guide_scrim)
        badgeDrawable = mainActivity.getDrawable(R.drawable.ic_tv_app_96x96)
        setSearchResultProvider(searchResultProvider)
        setOnItemViewClickedListener(itemClickedListener)
        return v
    }

    override fun onResume() {
        super.onResume()
        val searchBar = requireView().findViewById<SearchBar>(R.id.lb_search_bar)
        searchBar.setSearchQuery("")
        resultAdapter.clear()
    }

    private fun searchAndRefresh(query: String) {
        // TODO: Direkt im ProgramDataManager suchen (Performance), kommende Sendungen einbeziehen.
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            // DataManagerSearch wartet auf den Main-Thread, daher im Hintergrund suchen
            val results = withContext(TvSingletons.getSingletons(mainActivity).getDbDispatcher()) {
                search.search(query, SEARCH_RESULT_MAX, SearchInterface.ACTION_TYPE_AMBIGUOUS)
            }
            onSearchFinished(query, results)
            searchJob = null
        }
    }

    private fun onSearchFinished(query: String, results: List<LocalSearchProvider.SearchResult>?) {
        resultAdapter.clear()
        if (DEBUG) Log.d(TAG, "searchAndRefresh query=$query results=${results?.size ?: 0}")
        val resultsAdapter = ArrayObjectAdapter(presenter)
        val header = if (results.isNullOrEmpty()) {
            HeaderItem(0, mainActivity.getString(R.string.search_result_no_result))
        } else {
            resultsAdapter.addAll(0, results)
            HeaderItem(0, mainActivity.getString(R.string.search_result_title))
        }
        resultAdapter.add(ListRow(header, resultsAdapter))
    }

    companion object {
        private const val TAG = "ProgramGuideSearch"
        private const val DEBUG = false
        private const val SEARCH_RESULT_MAX = 10

        private fun createImageLoaderCallback(cardView: ImageCardView) =
            object : ImageLoader.ImageLoaderCallback<ImageCardView>(cardView) {
                override fun onBitmapLoaded(referent: ImageCardView, bitmap: Bitmap?) {
                    referent.mainImage = BitmapDrawable(referent.context.resources, bitmap)
                }
            }
    }
}
