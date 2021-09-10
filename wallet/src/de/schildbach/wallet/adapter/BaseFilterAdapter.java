package de.schildbach.wallet.adapter;

import android.annotation.SuppressLint;
import android.widget.Filter;
import android.widget.Filterable;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;


public abstract class BaseFilterAdapter<T, S extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<S> implements Filterable {

    protected List<T> mOriginalList = new ArrayList<>();
    protected List<T> mFilteredList = new ArrayList<>();
    private CustomFilter filter;
    private ResetViewListener viewListener;

    public BaseFilterAdapter(ResetViewListener viewListener) {
        this.viewListener = viewListener;
    }

    public void addItem(T object) {
        mOriginalList.add(object);
        notifyDataSetChanged();
    }

    public void setItems(List<T> arrayList) {
        this.mOriginalList = arrayList;
        this.mFilteredList = arrayList;
    }

    public List<T> getOriginalList() {
        return mOriginalList;
    }

    public List<T> getFilteredList() {
        return mFilteredList;
    }

    public T getListItem(int position) {
        if (position > mFilteredList.size()) {
            return null;
        }
        return mFilteredList.get(position);
    }

    @Override
    public int getItemCount() {
        return mFilteredList != null ? mFilteredList.size() : 0;
    }

    @Override
    public Filter getFilter() {
        if(filter == null) {
            filter = new CustomFilter();
        }
        return filter;
    }

    private class CustomFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults filterResults = new FilterResults();
            if (constraint != null && constraint.length() > 0) {
                constraint = constraint.toString().toLowerCase().trim();
                ArrayList<T> filteredList = new ArrayList<>();
                for (T object : mOriginalList) {
                    filterObject(filteredList, object, constraint);
                }
                filterResults.values = filteredList;
                filterResults.count = filteredList.size();
            } else {
                filterResults.values = mOriginalList;
                filterResults.count = mOriginalList.size();
            }
            return filterResults;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        protected void publishResults(CharSequence charSequence, FilterResults filterResults) {
            mFilteredList = (List<T>) filterResults.values;
            notifyDataSetChanged();
            viewListener.setViewState();
        }
    }

    protected abstract void filterObject(List<T> filteredList, T object, CharSequence searchText);

    public interface ResetViewListener {
        void setViewState();
    }
}