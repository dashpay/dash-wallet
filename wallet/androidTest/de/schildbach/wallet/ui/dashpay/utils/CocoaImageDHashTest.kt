package de.schildbach.wallet.ui.dashpay.utils

import android.graphics.Bitmap
import org.dash.wallet.common.ui.avatar.CocoaImageDHash
import org.dash.wallet.common.ui.avatar.ProfilePictureHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer

class CocoaImageDHashTest {

    // pixel data of bitmaps resided on iOS with usingInterpolation:NSImageInterpolationHigh
    // https://github.com/ameingast/cocoaimagehashing/blob/master/CocoaImageHashing/OSCategories.m#L96
    
    private val batmanPixels = arrayOf(
            "3d6375ff", "538096ff", "477286ff", "395e6eff", "33535fff", "335460ff", "345a69ff", "396376ff", "325c6eff",
            "527e94ff", "56849aff", "4b7588ff", "3e6372ff", "324b55ff", "31505cff", "345763ff", "3d6578ff", "3b667aff",
            "6495aeff", "517c90ff", "3c5d6aff", "41626fff", "273339ff", "2a4651ff", "335561ff", "335766ff", "3d687eff",
            "6ea1bbff", "4f788cff", "395b68ff", "395c69ff", "1d282dff", "2b434dff", "335561ff", "2c4b58ff", "3f697cff",
            "81b3caff", "557e93ff", "355765ff", "466876ff", "65777fff", "1b262bff", "304d58ff", "2d4b57ff", "305260ff",
            "8dbccdff", "567e90ff", "355666ff", "496674ff", "475358ff", "0b1014ff", "2e4651ff", "2a4854ff", "2b4b57ff",
            "93b0b7ff", "5a8499ff", "395e70ff", "395560ff", "3c4950ff", "0d1013ff", "2d4752ff", "284653ff", "1f3846ff",
            "7aa0b2ff", "4c7488ff", "305463ff", "405b66ff", "4a5556ff", "171b1bff", "2d4551ff", "284654ff", "142833ff",
            "6790a4ff", "375867ff", "2d5161ff", "576665ff", "3b392fff", "121516ff", "233a44ff", "254351ff", "10222cff"
    )

    private val deadpoolPixels = arrayOf(
            "fdfdfdff", "fdfdfdff", "fdfdfdff", "ffffffff", "f6ebedff", "e2c6caff", "fdfefeff", "fdfdfdff", "fdfdfdff",
            "fdfdfdff", "fdfdfdff", "fbfbfbff", "f1f2f3ff", "c1a5aaff", "793641ff", "f4eff0ff", "fefefeff", "fdfdfdff",
            "fdfdfdff", "fdfdfdff", "fcfcfcff", "cecfd2ff", "9c7e85ff", "5c464eff", "d3d3d5ff", "fefefeff", "fdfdfdff",
            "fdfdfdff", "fdfdfdff", "ffffffff", "d1d3d5ff", "894952ff", "6f5c62ff", "dbdcdeff", "ffffffff", "fdfdfdff",
            "fdfdfdff", "fefefeff", "e8eaebff", "796d73ff", "47151aff", "2d2a2fff", "b7bbbeff", "ffffffff", "fdfdfdff",
            "fdfdfdff", "ffffffff", "98a0a4ff", "201a21ff", "421c21ff", "231217ff", "464b50ff", "fafafaff", "fdfdfdff",
            "fdfdfdff", "f9f8f8ff", "725861ff", "35171eff", "2b1014ff", "260e12ff", "443940ff", "eef0f1ff", "fefefeff",
            "ffffffff", "e4cbcfff", "942734ff", "401217ff", "391519ff", "260e11ff", "782a35ff", "e1d5d8ff", "ffffffff",
            "ffffffff", "c8a5aaff", "6a1c27ff", "483b3dff", "341217ff", "180608ff", "591019ff", "d2b3b7ff", "ffffffff"
    )

    private val flashPixels = arrayOf(
            "d1a77eff", "f0d9a5ff", "f9f2bdff", "f9f7c1ff", "e0be92ff", "c1835dff", "9d6545ff", "a39585ff", "998376ff",
            "cda27aff", "d1875bff", "dcb283ff", "ebdfadff", "a72924ff", "a94933ff", "a35131ff", "a98c76ff", "937d71ff",
            "c68f67ff", "b33618ff", "ad331bff", "ce7b47ff", "aa2819ff", "a6472fff", "97422aff", "aa6049ff", "947f71ff",
            "bc714eff", "d4966eff", "9b2e28ff", "9c2213ff", "a6422bff", "ae5837ff", "93563dff", "8f3427ff", "8b7169ff",
            "a75636ff", "d3a178ff", "b36149ff", "8b120dff", "aa5949ff", "ae412dff", "b04828ff", "83271dff", "9c4f45ff",
            "9b8666ff", "c08961ff", "b55f41ff", "b6371aff", "b84a29ff", "811614ff", "8a1512ff", "b7461bff", "cb791dff",
            "9d9071ff", "bb8d60ff", "b7603eff", "aa361dff", "c04d28ff", "833127ff", "94413aff", "c85a3eff", "993b21ff",
            "a69979ff", "b48b5cff", "c34f2bff", "851412ff", "8f1b13ff", "b47357ff", "975f4eff", "a06b62ff", "937d8eff",
            "a29375ff", "9f704bff", "a42617ff", "822112ff", "b85426ff", "b57a60ff", "70442cff", "735654ff", "8a8da5ff"
    )

    private val ironmanPixels = arrayOf(
            "0256b0ff", "0156b0ff", "0165b8ff", "007ec3ff", "0780bdff", "0284c5ff", "016fbcff", "0158b1ff", "0054aeff",
            "0155afff", "0058b0ff", "0170bcff", "098bc4ff", "767a86ff", "189ccdff", "007ec4ff", "015fb5ff", "0054aeff",
            "0255afff", "005eb5ff", "1275b3ff", "3a7b9aff", "839a8fff", "17b8dcff", "0091ceff", "0169b9ff", "0054afff",
            "0255afff", "0063b8ff", "2584bdff", "8a7f90ff", "6b3837ff", "589ba9ff", "02a4d9ff", "0170bdff", "0056b0ff",
            "0155afff", "0066baff", "167aaeff", "704143ff", "922632ff", "b1a2a2ff", "08aadaff", "0074beff", "0156b0ff",
            "0155afff", "0164b7ff", "0292cfff", "224859ff", "8d1e27ff", "af9292ff", "05a8d8ff", "0172beff", "0156b0ff",
            "0155afff", "0160b5ff", "008ccfff", "2a7798ff", "880f16ff", "915151ff", "089bcfff", "006dbcff", "0156afff",
            "0155afff", "015bb2ff", "007ac3ff", "178abaff", "963336ff", "a93e3bff", "257dafff", "0065b9ff", "0155afff",
            "0255afff", "0157b0ff", "006dbdff", "1d73abff", "972832ff", "a04d4aff", "2f699dff", "005eb7ff", "0054aeff"
    )

    private val spidermanPixels = arrayOf(
            "102233ff", "102437ff", "19324aff", "315677ff", "5c85a9ff", "385e82ff", "203b56ff", "13293dff", "0d2134ff",
            "0f2133ff", "12263aff", "193651ff", "375a81ff", "9cacc2ff", "4d7295ff", "233f5cff", "142b40ff", "0c2033ff",
            "102233ff", "152b40ff", "22354cff", "403c4eff", "6f4852ff", "496b8dff", "1d3c58ff", "22384dff", "3b4b5aff",
            "102334ff", "242b40ff", "352936ff", "3a353eff", "522a2dff", "435673ff", "3a556cff", "384b5eff", "192d3fff",
            "102333ff", "473443ff", "4d373dff", "283240ff", "42232aff", "455973ff", "28445aff", "162b3fff", "112332ff",
            "142434ff", "394855ff", "354659ff", "364659ff", "343b48ff", "172b40ff", "2e485cff", "314652ff", "1d343dff",
            "28343eff", "2a3d4aff", "303f51ff", "433a48ff", "382733ff", "283142ff", "445865ff", "334048ff", "2f393fff",
            "2a333bff", "2e3942ff", "25394aff", "374a5eff", "2f3144ff", "20374bff", "3a4955ff", "2f383dff", "262c33ff",
            "192430ff", "1e2a36ff", "142738ff", "192e44ff", "1f354eff", "273a4bff", "3f4e5bff", "282f35ff", "252a2fff"
    )

    private val supermanPixels = arrayOf(
            "01000003", "16060620", "03010105", "00000000", "00000000", "00000002", "00000001", "00000000", "00000002",
            "02010104", "350e0e45", "410e0e58", "0c020216", "06060716", "01000002", "0a07050c", "32282258", "00000001",
            "00000001", "01010001", "6d171992", "9c3d3ae3", "8f7764d5", "0d0f111f", "1c3e5a79", "2d2d3b8b", "0800010a",
            "00000002", "00000000", "34405799", "ba7360ff", "846968fb", "3c4a71e0", "44304ccd", "3a090e6d", "37060956",
            "00000000", "1d26304e", "3e4658ac", "7a788dfa", "4f526afa", "a31d22ff", "ae0b14ff", "55070bd9", "04010116",
            "00000001", "26221d3f", "0c070613", "684e56d0", "5c4455ff", "39111593", "1d050655", "1503032b", "00000001",
            "00000002", "00000000", "00000000", "04090f1b", "1b395682", "232335a7", "00000000", "00000000", "00000002",
            "00000002", "00000002", "00000002", "00000000", "00000000", "450f1264", "25060737", "00000000", "00000002",
            "00000002", "00000002", "00000002", "00000002", "00000001", "0701010c", "0c020313", "00000001", "00000002"
    )

    private val dashPixels = arrayOf(
            "00000000", "001e3137", "001d2f35", "00000000", "00000000", "00000000", "00030405", "00111b1e", "00000000",
            "00000000", "00487482", "0072b8ce", "00101a1d", "00090e10", "00050809", "00172529", "00416975", "00010202",
            "00121d21", "0029424a", "00365762", "00518393", "006aacc1", "00568b9c", "00528594", "0075bdd4", "002d4952",
            "00284149", "00456f7c", "00385a65", "00578d9d", "00456f7d", "0065a3b7", "004a7886", "00518393", "0033535c",
            "00040708", "00385b66", "005b93a4", "0055899a", "00548898", "004a7886", "006badc2", "004d7c8b", "002c4750",
            "00090f11", "00558999", "002b454d", "002c474f", "00477380", "00446e7b", "00375964", "002d4952", "0017262a",
            "00000000", "00000000", "000d1517", "000c1417", "00090f10", "000a1012", "00090e10", "00010101", "00000000",
            "00000000", "00000000", "00263d44", "003d636f", "0034545e", "002a444c", "00395c67", "000b1214", "00000000",
            "00000000", "00000000", "00050809", "000e1719", "0006090a", "0006090b", "00060a0b", "00010101", "00000000"
    )

    @Test
    fun of() {
        val batmanBmp = asBitmap(batmanPixels)
        assertEquals("8e0e0b4b531bdfdb", CocoaImageDHash.of(batmanBmp).toString(16))
        val deadpoolPixelsBmp = asBitmap(deadpoolPixels)
        assertEquals("589e9e8c8e161f1f", CocoaImageDHash.of(deadpoolPixelsBmp).toString(16))
        val flashBmp = asBitmap(flashPixels)
        assertEquals("b8892b6656169627", CocoaImageDHash.of(flashBmp).toString(16))
        val ironmanBmp = asBitmap(ironmanPixels)
        assertEquals("e1e0f0e8ece4ccc8", CocoaImageDHash.of(ironmanBmp).toString(16))
        val spidermanBmp = asBitmap(spidermanPixels)
        assertEquals("f0f060e8ecdac8da", CocoaImageDHash.of(spidermanBmp).toString(16))
        val supermanBmp = asBitmap(supermanPixels)
        assertEquals("694d0f8f8fa3060", CocoaImageDHash.of(supermanBmp).toString(16))
        val dashBmp = asBitmap(dashPixels)
        assertEquals("869cb0aadcf2ecd8", CocoaImageDHash.of(dashBmp).toString(16))
    }

    private fun asBitmap(pixels: Array<String>): Bitmap {
        val width = 9
        val height = 9
        var pixelsByteArray = ProfilePictureHelper.toByteArray(BigInteger(pixels.joinToString(""), 16))
        val tmp = ByteArray(width * height * 4)
        if (pixelsByteArray.size < tmp.size) {
            // add leading zeros
            val destPos = tmp.size - pixelsByteArray.size
            System.arraycopy(pixelsByteArray, 0, tmp, destPos, pixelsByteArray.size)
            pixelsByteArray = tmp
        }
        val buffer = ByteBuffer.wrap(pixelsByteArray)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buffer)
        }
    }
}