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

import android.arch.persistence.room.TypeConverter;

import org.bitcoinj.core.Coin;

/**
 * @author Samuel Barbosa
 */
public class CoinConverter {

    @TypeConverter
    public static Coin fromLong(long value) {
        return Coin.valueOf(value);
    }

    @TypeConverter
    public static long fromCoin(Coin value) {
        return value.value;
    }

}
