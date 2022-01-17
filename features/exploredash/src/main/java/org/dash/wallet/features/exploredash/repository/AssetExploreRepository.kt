/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.features.exploredash.repository

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.util.*
import javax.inject.Inject

private val log = LoggerFactory.getLogger(AssetExploreDatabase::class.java)

class AssetExploreDatabase @Inject constructor() : ExploreRepository {

    var contextRef: WeakReference<Context?> = WeakReference(null)

    override suspend fun <T> get(
        tableName: String,
        startAt: Int,
        endBefore: Int,
        valueType: Class<T>
    ): List<T> {
        return withContext(Dispatchers.IO) {
            val result = mutableListOf<T>()
            try {
                Scanner(contextRef.get()!!.assets.open("explore/$tableName.dat")).use {
                    it.nextLine() // skip the update date
                    it.nextLine() // skip the data size
                    for (i in 0 until startAt) { // the hacky way to simulate pagination
                        it.nextLine()
                    }
                    while (it.hasNextLine()) {
                        val line = it.nextLine()
                        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                        val jsonAdapter = moshi.adapter<T>(valueType)
                        val data = jsonAdapter.fromJson(line)
                        result.add(data!! as T)
                        if (result.size == (endBefore - startAt)) {
                            break
                        }
                    }
                }
            } catch (ex: Exception) {
                log.error(ex.message, ex)
            }
            return@withContext result
        }
    }

    override suspend fun getDataSize(tableName: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                Scanner(contextRef.get()!!.assets.open("explore/$tableName.dat")).use {
                    it.nextLine() // skip the update date
                    if (it.hasNextLine()) { // second line is the the update date
                        val dataSize = it.nextLine()
                        return@withContext dataSize.toInt()
                    }
                }
            } catch (ex: Exception) {
                log.error(ex.message, ex)
            }
            return@withContext -1
        }
    }

    override suspend fun getLastUpdate(): Long {
        return getLastUpdate(FirebaseExploreDatabase.DCG_MERCHANT_TABLE)
    }

    override suspend fun getLastUpdate(tableName: String): Long {
        return withContext(Dispatchers.IO) {
            try {
                Scanner(contextRef.get()!!.assets.open("explore/$tableName.dat")).use {
                    if (it.hasNextLine()) { // very first line is the the update date
                        val updateDate = it.nextLine()
                        return@withContext updateDate.toLong()
                    }
                }
            } catch (ex: Exception) {
                log.error(ex.message, ex)
            }
            return@withContext -1
        }
    }
}