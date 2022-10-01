package com.aghajari.wearcam

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.ambient.AmbientModeSupport.AmbientCallback
import com.aghajari.opwear.OpWear
import com.aghajari.wearcam.databinding.ActivityMainBinding
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(),
    OpWear.OnConnectionChangedListener,
    MessageClient.OnMessageReceivedListener,
    AmbientModeSupport.AmbientCallbackProvider {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OpWear.install(this)
        OpWear.autoValidationStrategy = OpWear.AutoValidationStrategy.RESPONSE

        AmbientModeSupport.attach(this)

        binding.image.setOnClickListener {
            lifecycleScope.launch {
                if (OpWear.sendMessage("TakePicture"))
                    Toast.makeText(it.context, "Picture taken", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onConnectionChange(status: OpWear.ConnectionStatus) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (status == OpWear.ConnectionStatus.CONNECTED) {
                binding.text.text = OpWear.connectedNodeDisplayName
            } else {
                binding.text.setText(R.string.connecting)
                binding.image.setImageBitmap(null)
            }
        }
    }

    override fun onMessageReceived(msg: MessageEvent) {
        if (msg.path == "Bitmap") {
            binding.image.setImageBitmap(
                BitmapFactory.decodeByteArray(
                    msg.data, 0, msg.data.size
                )
            )
        }
    }

    override fun getAmbientCallback() =
        object : AmbientCallback() {
            override fun onEnterAmbient(ambientDetails: Bundle) {
                super.onEnterAmbient(ambientDetails)
            }
        }
}