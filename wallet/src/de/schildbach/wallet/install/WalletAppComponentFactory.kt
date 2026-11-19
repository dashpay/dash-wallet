/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.install

import android.app.Activity
import android.app.Application
import android.app.Service
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.app.CoreComponentFactory

/**
 * Keeps an install that lacks its native libraries from crashing at launch.
 *
 * The system creates this factory before the application class and asks it for
 * every component. When [NativeLibraryCheck] finds the libraries affirmatively
 * missing, it returns inert stand-ins instead of the real classes, so nothing
 * that could load native code ever runs: no Hilt graph, no
 * `WalletApplication.onCreate`, no receiver, service, job or provider. Every
 * activity becomes [IncompleteInstallActivity], which asks the user to install
 * the app from Google Play.
 *
 * In every other case ([NativeLibraryCheck.State.PRESENT] or
 * [NativeLibraryCheck.State.UNKNOWN]) it defers to [CoreComponentFactory], the
 * factory AndroidX declares by default.
 */
class WalletAppComponentFactory : CoreComponentFactory() {

    override fun instantiateClassLoader(cl: ClassLoader, aInfo: ApplicationInfo): ClassLoader {
        NativeLibraryCheck.inspectOnce(aInfo)
        return super.instantiateClassLoader(cl, aInfo)
    }

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        return if (NativeLibraryCheck.isIncompleteInstall) {
            IncompleteInstallApplication()
        } else {
            super.instantiateApplication(cl, className)
        }
    }

    override fun instantiateActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
        return if (NativeLibraryCheck.isIncompleteInstall) {
            IncompleteInstallActivity()
        } else {
            super.instantiateActivity(cl, className, intent)
        }
    }

    override fun instantiateReceiver(cl: ClassLoader, className: String, intent: Intent?): BroadcastReceiver {
        return if (NativeLibraryCheck.isIncompleteInstall) {
            InertReceiver()
        } else {
            super.instantiateReceiver(cl, className, intent)
        }
    }

    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service {
        if (!NativeLibraryCheck.isIncompleteInstall) {
            return super.instantiateService(cl, className, intent)
        }
        // A job that the scheduler binds expects a JobService binder. Loading the
        // class does not initialize it, so no static code of the real service runs.
        val isJobService = try {
            JobService::class.java.isAssignableFrom(cl.loadClass(className))
        } catch (e: Exception) {
            false
        } catch (e: LinkageError) {
            false
        }
        return if (isJobService) InertJobService() else InertService(className)
    }

    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider {
        // Firebase's provider only sets up FirebaseApp, which the incomplete-install
        // application uses to report the install to Crashlytics. Every other provider
        // (the wallet's own, AndroidX startup, WorkManager) is replaced.
        return if (NativeLibraryCheck.isIncompleteInstall && className != FIREBASE_INIT_PROVIDER) {
            InertProvider()
        } else {
            super.instantiateProvider(cl, className)
        }
    }

    private companion object {
        const val FIREBASE_INIT_PROVIDER = "com.google.firebase.provider.FirebaseInitProvider"
    }
}
