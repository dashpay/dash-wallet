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

package de.schildbach.wallet.ui;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.ViewModel;

import org.bitcoinj.core.Transaction;

import de.schildbach.wallet.data.BlockchainUser;
import de.schildbach.wallet.data.BlockchainUserRepository;
import de.schildbach.wallet.data.Resource;

/**
 * @author Samuel Barbosa
 */
public class BlockchainUserViewModel extends ViewModel {

    BlockchainUserRepository repository = BlockchainUserRepository.getInstance();

    public LiveData<Resource<Transaction>> createBlockchainUser(String username, byte[] encryptionKey) {
        return repository.createBlockchainUser(username, encryptionKey);
    }

    public LiveData<Resource<BlockchainUser>> getUser() {
        return repository.getUser();
    }

}
