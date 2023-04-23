/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.features.exploredash.network.service

import org.dash.wallet.features.exploredash.data.dashdirect.model.signin.CreateUserResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.signin.SignInResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.signin.VerifyEmailRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.signin.VerifyEmailResponse
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface DashDirectAuthApi {
    @PUT("SignIn")
    suspend fun signIn(@Query("emailAddress") email: String): SignInResponse?

    @POST("CreateUser")
    suspend fun createUser(@Query("emailAddress") email: String): CreateUserResponse?

    @PUT("VerifyEmail")
    suspend fun verifyEmail(@Body signInRequest: VerifyEmailRequest): VerifyEmailResponse?
}
