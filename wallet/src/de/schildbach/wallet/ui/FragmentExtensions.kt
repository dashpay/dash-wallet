package de.schildbach.wallet.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.activity_payments.*

fun Fragment.setupActionBarWithTitle(id: Int, upAsHomeEnable: Boolean = true) {
    toolbar.setTitle(id)
    val appCompatActivity = requireActivity() as AppCompatActivity
    appCompatActivity.setSupportActionBar(toolbar)
    val actionBar = appCompatActivity.supportActionBar
    if (upAsHomeEnable) {
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }
}
