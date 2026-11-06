package com.mhmmtbg.x5reframe

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Giriş: klip seç, düzenleyiciyi aç. */
class MainActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.infoText).text =
            "Kameranın SD kartındaki DCIM/Camera01 klasöründen VID_…_00_xxx.insv dosyasını seç.\n\n" +
                "Önizlemede sürükleyerek yön, iki parmakla açı değiştir. Kare + ile anahtar kare ekle; " +
                "Başla/Bitir ile kes; Dışa aktar ile Filmler/X5Reframe klasörüne kaydet."
        findViewById<Button>(R.id.pickButton).setOnClickListener {
            picker.launch(arrayOf("video/*", "application/octet-stream", "*/*"))
        }
    }
}
