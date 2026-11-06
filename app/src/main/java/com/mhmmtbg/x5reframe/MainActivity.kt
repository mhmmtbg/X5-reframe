package com.mhmmtbg.x5reframe

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mhmmtbg.x5reframe.ui.theme.Palette
import com.mhmmtbg.x5reframe.ui.theme.X5Theme

/** Giriş ekranı: klip seç, düzenleyiciyi aç. */
class MainActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startActivity(Intent(this, EditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            X5Theme {
                Column(
                    Modifier.fillMaxSize().background(Palette.Background).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(96.dp))
                    Text("X5 Reframe", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Insta360 X5 360° kliplerini telefonunda kadrajla, kes ve hızlıca düz video olarak dışa aktar.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("video/*", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(12.dp), elevation = null,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                    ) { Text("Klip seç") }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "SD karttaki DCIM/Camera01 klasöründen VID_…_00_xxx.insv dosyasını seç (LRV değil).",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
