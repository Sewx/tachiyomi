package eu.kanade.mangafeed.ui.manga.chapter;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;

import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;
import eu.kanade.mangafeed.R;
import eu.kanade.mangafeed.data.database.models.Chapter;
import eu.kanade.mangafeed.data.database.models.Manga;
import eu.kanade.mangafeed.data.download.DownloadService;
import eu.kanade.mangafeed.event.DownloadStatusEvent;
import eu.kanade.mangafeed.ui.base.activity.BaseActivity;
import eu.kanade.mangafeed.ui.base.fragment.BaseRxFragment;
import eu.kanade.mangafeed.ui.decoration.DividerItemDecoration;
import eu.kanade.mangafeed.ui.manga.MangaActivity;
import eu.kanade.mangafeed.ui.reader.ReaderActivity;
import eu.kanade.mangafeed.util.EventBusHook;
import eu.kanade.mangafeed.util.ToastUtil;
import nucleus.factory.RequiresPresenter;
import rx.Observable;

@RequiresPresenter(ChaptersPresenter.class)
public class ChaptersFragment extends BaseRxFragment<ChaptersPresenter> implements
        ActionMode.Callback, ChaptersAdapter.OnItemClickListener {

    @Bind(R.id.chapter_list) RecyclerView chapters;
    @Bind(R.id.swipe_refresh) SwipeRefreshLayout swipeRefresh;
    @Bind(R.id.toolbar_bottom) Toolbar toolbarBottom;

    @Bind(R.id.action_sort) ImageView sortBtn;
    @Bind(R.id.action_next_unread) ImageView nextUnreadBtn;
    @Bind(R.id.action_show_unread) CheckBox readCb;
    @Bind(R.id.action_show_downloaded) CheckBox downloadedCb;

    private ChaptersAdapter adapter;

    private ActionMode actionMode;

    public static ChaptersFragment newInstance() {
        return new ChaptersFragment();
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setHasOptionsMenu(true);
        getPresenter().setIsCatalogueManga(isCatalogueManga());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_manga_chapters, container, false);
        ButterKnife.bind(this, view);

        // Init RecyclerView and adapter
        chapters.setLayoutManager(new LinearLayoutManager(getActivity()));
        chapters.addItemDecoration(new DividerItemDecoration(ContextCompat.getDrawable(this.getContext(), R.drawable.line_divider)));
        adapter = new ChaptersAdapter(this);
        chapters.setAdapter(adapter);

        // Set initial values
        setReadFilter();
        setSortIcon();

        // Init listeners
        swipeRefresh.setOnRefreshListener(this::onFetchChapters);
        readCb.setOnCheckedChangeListener((arg, isChecked) -> getPresenter().setReadFilter(isChecked));
        sortBtn.setOnClickListener(v -> {
            getPresenter().revertSortOrder();
            setSortIcon();
        });
        nextUnreadBtn.setOnClickListener(v -> {
            Chapter chapter = getPresenter().getNextUnreadChapter();
            if (chapter != null) {
                openChapter(chapter);
            } else {
                ToastUtil.showShort(getContext(), R.string.no_next_chapter);
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        registerForEvents();
    }

    @Override
    public void onPause() {
        unregisterForEvents();
        super.onPause();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.chapters, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_refresh:
                onFetchChapters();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    public void onNextChapters(List<Chapter> chapters) {
        closeActionMode();
        adapter.setItems(chapters);
    }

    public void onFetchChapters() {
        swipeRefresh.setRefreshing(true);
        getPresenter().fetchChapters();
    }

    public void onFetchChaptersFinish() {
        swipeRefresh.setRefreshing(false);
    }

    public boolean isCatalogueManga() {
        return ((MangaActivity) getActivity()).isCatalogueManga();
    }

    protected void openChapter(Chapter chapter) {
        getPresenter().onOpenChapter(chapter);
        Intent intent = ReaderActivity.newIntent(getActivity());
        startActivity(intent);
    }

    @EventBusHook
    public void onEventMainThread(DownloadStatusEvent event) {
        Manga manga = getPresenter().getManga();
        // If the download status is from another manga, don't bother
        if (manga != null && event.getChapter().manga_id != manga.id)
            return;

        Chapter chapter;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            chapter = adapter.getItem(i);
            if (event.getChapter().id == chapter.id) {
                chapter.status = event.getStatus();
                adapter.notifyItemChanged(i);
                break;
            }
        }
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(R.menu.chapter_selection, menu);
        adapter.setMode(ChaptersAdapter.MODE_MULTI);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_select_all:
                return onSelectAll();
            case R.id.action_mark_as_read:
                return onMarkAsRead(getSelectedChapters());
            case R.id.action_mark_as_unread:
                return onMarkAsUnread(getSelectedChapters());
            case R.id.action_download:
                return onDownload(getSelectedChapters());
            case R.id.action_delete:
                return onDelete(getSelectedChapters());
        }
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        adapter.setMode(ChaptersAdapter.MODE_SINGLE);
        adapter.clearSelection();
        actionMode = null;
    }

    private Observable<Chapter> getSelectedChapters() {
        return Observable.from(adapter.getSelectedItems())
                .map(adapter::getItem);
    }

    public void closeActionMode() {
        if (actionMode != null)
            actionMode.finish();
    }

    protected boolean onSelectAll() {
        adapter.selectAll();
        setContextTitle(adapter.getSelectedItemCount());
        actionMode.invalidate();
        return true;
    }

    protected boolean onMarkAsRead(Observable<Chapter> chapters) {
        getPresenter().markChaptersRead(chapters, true);
        return true;
    }

    protected boolean onMarkAsUnread(Observable<Chapter> chapters) {
        getPresenter().markChaptersRead(chapters, false);
        return true;
    }

    protected boolean onDownload(Observable<Chapter> chapters) {
        DownloadService.start(getActivity());
        getPresenter().downloadChapters(chapters);
        closeActionMode();
        return true;
    }

    protected boolean onDelete(Observable<Chapter> chapters) {
        getPresenter().deleteChapters(chapters);
        closeActionMode();
        return true;
    }

    @Override
    public boolean onListItemClick(int position) {
        if (actionMode != null && adapter.getMode() == ChaptersAdapter.MODE_MULTI) {
            toggleSelection(position);
            return true;
        } else {
            openChapter(adapter.getItem(position));
            return false;
        }
    }

    @Override
    public void onListItemLongClick(int position) {
        if (actionMode == null)
            actionMode = ((BaseActivity) getActivity()).startSupportActionMode(this);

        toggleSelection(position);
    }

    private void toggleSelection(int position) {
        adapter.toggleSelection(position, false);

        int count = adapter.getSelectedItemCount();

        if (count == 0) {
            actionMode.finish();
        } else {
            setContextTitle(count);
            actionMode.invalidate();
        }
    }

    private void setContextTitle(int count) {
        actionMode.setTitle(getString(R.string.selected_chapters_title, count));
    }

    public void setSortIcon() {
        if (sortBtn != null) {
            boolean aToZ = getPresenter().getSortOrder();
            sortBtn.setImageResource(!aToZ ? R.drawable.ic_expand_less_white_36dp : R.drawable.ic_expand_more_white_36dp);
        }
    }

    public void setReadFilter() {
        if (readCb != null) {
            readCb.setChecked(getPresenter().getReadFilter());
        }
    }
}