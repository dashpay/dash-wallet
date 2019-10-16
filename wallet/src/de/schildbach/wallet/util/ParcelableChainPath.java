package de.schildbach.wallet.util;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableList;

import org.bitcoinj.crypto.ChildNumber;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Sam Barbosa on 5/22/2018.
 */
public class ParcelableChainPath implements Parcelable {

    private int[] childNumberArr;

    public ParcelableChainPath(List<ChildNumber> childNumberList) {
        int size = childNumberList.size();
        childNumberArr = new int[size];
        int i = 0;
        while (i < size) {
            childNumberArr[i] = childNumberList.get(i).getI();
            i++;
        }
    }

    public ParcelableChainPath(Parcel source) {
        childNumberArr = source.createIntArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeIntArray(childNumberArr);
    }

    public static final Parcelable.Creator<ParcelableChainPath> CREATOR = new Parcelable.Creator<ParcelableChainPath>() {

        @Override
        public ParcelableChainPath createFromParcel(Parcel source) {
            return new ParcelableChainPath(source);
        }

        @Override
        public ParcelableChainPath[] newArray(int size) {
            return new ParcelableChainPath[size];
        }

    };

    @NonNull
    public ImmutableList<ChildNumber> getPath() {
        List<ChildNumber> childNumberList = new ArrayList<>();
        for (int i : childNumberArr) {
            childNumberList.add(new ChildNumber(i));
        }
        return ImmutableList.copyOf(childNumberList);
    }

}
