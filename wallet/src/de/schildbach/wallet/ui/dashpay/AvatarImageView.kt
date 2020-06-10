package de.schildbach.wallet.ui.dashpay

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.Glide
import de.schildbach.wallet_test.R

class AvatarImageView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : androidx.appcompat.widget.AppCompatImageView(context, attrs, defStyleAttr) {

    fun setUrl(url: String) {
        Glide.with(this).load(url).circleCrop()
                .placeholder(R.drawable.user5).into(this)
    }

    fun setDefaultUserAvatar(letters: String) {
        val hsv = FloatArray(3)
        //Ascii codes for A: 65 - Z: 90, 0: 48 - 9: 57
        val firstChar = letters[0].toFloat()
        val charIndex: Float
        charIndex = if (firstChar <= 57) { //57 == '9' in Ascii table
            (firstChar - 48f) / 36f // 48 == '0', 36 == total count of supported
        } else {
            (firstChar - 65f + 10f) / 36f // 65 == 'A', 10 == count of digits
        }
        hsv[0] = charIndex * 360f
        hsv[1] = 0.3f
        hsv[2] = 0.6f
        val bgColor = Color.HSVToColor(hsv)
        val defaultAvatar = TextDrawable.builder().beginConfig().textColor(Color.WHITE)
                .useFont(ResourcesCompat.getFont(context, R.font.montserrat_regular))
                .endConfig().buildRound(letters[0].toString(), bgColor)
        background = defaultAvatar
    }
}