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

package de.schildbach.wallet.security;

/**
 * Exception thrown when SecurityGuard operations fail
 * Provides detailed error types for better error handling
 */
public class SecurityGuardException extends Exception {
    
    public enum ErrorType {
        KEY_NOT_FOUND,
        KEY_CORRUPTED,
        ENCRYPTION_FAILED,
        DECRYPTION_FAILED,
        KEYSTORE_ERROR,
        VALIDATION_FAILED,
        RECOVERY_POSSIBLE,
        UNRECOVERABLE_ERROR
    }
    
    private final ErrorType errorType;
    private final boolean isRecoverable;
    
    public SecurityGuardException(String message) {
        super(message);
        this.errorType = ErrorType.UNRECOVERABLE_ERROR;
        this.isRecoverable = false;
    }
    
    public SecurityGuardException(String message, Throwable cause) {
        super(message, cause);
        this.errorType = ErrorType.UNRECOVERABLE_ERROR;
        this.isRecoverable = false;
    }
    
    public SecurityGuardException(String message, ErrorType errorType) {
        super(message);
        this.errorType = errorType;
        this.isRecoverable = (errorType == ErrorType.RECOVERY_POSSIBLE);
    }
    
    public SecurityGuardException(String message, Throwable cause, ErrorType errorType) {
        super(message, cause);
        this.errorType = errorType;
        this.isRecoverable = (errorType == ErrorType.RECOVERY_POSSIBLE);
    }
    
    public SecurityGuardException(String message, Throwable cause, boolean isRecoverable) {
        super(message, cause);
        this.errorType = isRecoverable ? ErrorType.RECOVERY_POSSIBLE : ErrorType.UNRECOVERABLE_ERROR;
        this.isRecoverable = isRecoverable;
    }
    
    public SecurityGuardException(Throwable cause) {
        super(cause);
        this.errorType = ErrorType.UNRECOVERABLE_ERROR;
        this.isRecoverable = false;
    }
    
    public ErrorType getErrorType() {
        return errorType;
    }
    
    public boolean isRecoverable() {
        return isRecoverable;
    }
}