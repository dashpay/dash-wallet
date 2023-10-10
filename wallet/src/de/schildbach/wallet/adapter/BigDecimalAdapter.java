/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.adapter;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;

import java.math.BigDecimal;

public class BigDecimalAdapter {

    @ToJson String toJson(BigDecimal value) {
        return value.toPlainString();
    }

    @FromJson BigDecimal fromJson(String value) {
        return new BigDecimal(value);
    }

}
