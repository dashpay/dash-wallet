/*
 * Copyright © 2019 Dash Core Group. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.data;

import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.arch.persistence.room.TypeConverters;
import android.support.annotation.NonNull;

import org.bitcoinj.core.Coin;

import java.util.ArrayList;

/**
 * @author Samuel Barbosa
 */
@Entity(tableName = "blockchain_user")
@TypeConverters({StringListConverter.class, CoinConverter.class})
public class BlockchainUser {

    @PrimaryKey
    @NonNull
    private String regtxid;
    private String uname;
    private String pubkeyid;
    private Coin credits;
    private String data;
    private String state;
    private ArrayList<String> subtx;

    public BlockchainUser(@NonNull String regtxid, String uname, String pubkeyid, Coin credits,
                          String data, String state, ArrayList<String> subtx) {
        this.regtxid = regtxid;
        this.uname = uname;
        this.pubkeyid = pubkeyid;
        this.credits = credits;
        this.data = data;
        this.state = state;
        this.subtx = subtx;
    }

    public static BlockchainUser fromDapiClientObject(org.dashevo.dapiclient.model.BlockchainUser dapiBu) {
        return new BlockchainUser(dapiBu.getRegtxid(), dapiBu.getUname(), dapiBu.getPubkeyid(),
                Coin.valueOf(dapiBu.getCredits()), dapiBu.getData(), dapiBu.getState(), dapiBu.getSubtx());
    }

    @NonNull
    public String getRegtxid() {
        return regtxid;
    }

    public void setRegtxid(@NonNull String regtxid) {
        this.regtxid = regtxid;
    }

    public String getUname() {
        return uname;
    }

    public void setUname(String uname) {
        this.uname = uname;
    }

    public String getPubkeyid() {
        return pubkeyid;
    }

    public void setPubkeyid(String pubkeyid) {
        this.pubkeyid = pubkeyid;
    }

    public Coin getCredits() {
        return credits;
    }

    public void setCredits(Coin credits) {
        this.credits = credits;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public ArrayList<String> getSubtx() {
        return subtx;
    }

    public void setSubtx(ArrayList<String> subtx) {
        this.subtx = subtx;
    }

}
