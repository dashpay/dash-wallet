/*
 * Copyright 2025 Dash Core Group.
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

package org.dash.wallet.common.util.security

import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

object SecurityFileUtils {
    private val log = LoggerFactory.getLogger(SecurityFileUtils::class.java)
    
    const val SECURITY_BACKUP_DIR = "security_backup"
    const val MIGRATION_FLAGS_DIR = "migration_flags"
    const val FLAG_FILE_EXTENSION = ".flag"
    const val BACKUP_FILE_SUFFIX = "_backup.dat"
    const val BACKUP2_FILE_SUFFIX = "_backup2.dat"

    @Throws(IOException::class)
    fun writeToFile(file: File, data: String) {
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
            fos.write(data.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
        } finally {
            fos?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                    log.warn("Failed to close file output stream", e)
                }
            }
        }
    }

    fun readFromFile(file: File): String? {
        var fis: FileInputStream? = null
        return try {
            fis = FileInputStream(file)
            val buffer = ByteArray(file.length().toInt())
            fis.read(buffer)
            String(buffer, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            log.warn("Failed to read from file: {}", file.name, e)
            null
        } finally {
            fis?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                    log.warn("Failed to close file input stream", e)
                }
            }
        }
    }

    fun isMigrationCompleted(filesDir: File, migrationName: String): Boolean {
        return try {
            val migrationDir = File(filesDir, MIGRATION_FLAGS_DIR)
            val migrationFile = File(migrationDir, "$migrationName$FLAG_FILE_EXTENSION")
            migrationFile.exists()
        } catch (e: Exception) {
            log.warn("Failed to check migration flag: {}", migrationName, e)
            false
        }
    }

    fun setMigrationCompleted(filesDir: File, migrationName: String) {
        try {
            val migrationDir = File(filesDir, MIGRATION_FLAGS_DIR)
            if (!migrationDir.exists()) {
                migrationDir.mkdirs()
            }
            
            val migrationFile = File(migrationDir, "$migrationName$FLAG_FILE_EXTENSION")
            migrationFile.createNewFile()
            log.info("Migration flag set: {}", migrationName)
        } catch (e: Exception) {
            log.error("Failed to set migration flag: {}", migrationName, e)
        }
    }

    fun createBackupDir(filesDir: File): File {
        val backupDir = File(filesDir, SECURITY_BACKUP_DIR)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
        return backupDir
    }

    fun getBackupFile(filesDir: File, keyAlias: String, isPrimary: Boolean = true): File {
        val backupDir = createBackupDir(filesDir)
        val suffix = if (isPrimary) BACKUP_FILE_SUFFIX else BACKUP2_FILE_SUFFIX
        return File(backupDir, "$keyAlias$suffix")
    }

    fun clearBackupDirectory(filesDir: File) {
        try {
            val backupDir = File(filesDir, SECURITY_BACKUP_DIR)
            if (backupDir.exists()) {
                val backupFiles = backupDir.listFiles()
                backupFiles?.forEach { it.delete() }
                backupDir.delete()
                log.info("Cleared backup directory")
            }
        } catch (e: Exception) {
            log.error("Failed to clear backup directory", e)
        }
    }
}