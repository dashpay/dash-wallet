/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

class SyncProgressEvent @JvmOverloads constructor(val pct: Double, val failed: Boolean = false) {
    override fun toString(): String {
        if (failed) {
            return "sync failed"
        }
        return if (pct == 100.0) {
            "sync progress: DONE"
        } else {
            String.format("sync progress: %.2f", pct);
        }
    }
}