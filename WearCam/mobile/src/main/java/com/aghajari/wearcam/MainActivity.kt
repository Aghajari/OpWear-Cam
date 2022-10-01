package com.aghajari.wearcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.aghajari.wearcam.databinding.ActivityMainBinding
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

import com.aghajari.opwear.OpWear

class MainActivity : AppCompatActivity(),
    OpWear.OnConnectionChangedListener,
    MessageClient.OnMessageReceivedListener {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted)
            start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OpWear.install(this)
        checkCameraPermission()
    }

    override fun onConnectionChange(status: OpWear.ConnectionStatus) {
        lifecycleScope.launch(Dispatchers.Main) {
            when (status) {
                OpWear.ConnectionStatus.CONNECTED -> {
                    binding.text.text = OpWear.connectedNodeDisplayName
                    binding.indicator.visibility = View.GONE
                }
                OpWear.ConnectionStatus.CONNECTING -> {
                    binding.text.setText(R.string.connecting)
                    binding.indicator.visibility = View.VISIBLE
                }
                else -> OpWear.connect()
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
            start()
        else
            permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun start() {
        lifecycleScope.launch { OpWear.connect() }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.camera.surfaceProvider)

            future.get().bindToLifecycle(
                this,
                CameraSelector.Builder().build(),
                ImageAnalysis.Builder().build(),
                preview
            )

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        if (!OpWear.isConnected()) {
                            delay(1000)
                            continue
                        }

                        binding.camera.bitmap?.let {
                            val res = Bitmap.createScaledBitmap(
                                it,
                                150,
                                150 * resources.displayMetrics.heightPixels
                                        / resources.displayMetrics.widthPixels,
                                false
                            )

                            withContext(Dispatchers.IO) {
                                val stream = ByteArrayOutputStream()
                                res.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                OpWear.sendMessage("Bitmap", stream.toByteArray())
                                stream.close()
                            }
                        }
                    }
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onMessageReceived(msg: MessageEvent) {
        if (msg.path == "TakePicture") {
            binding.camera.bitmap?.let {
                try {
                    val stream = FileOutputStream(File(filesDir, "image.png"))
                    it.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.close()
                    Toast.makeText(this, "Picture taken", Toast.LENGTH_SHORT).show()
                } catch (ignore: Exception) {
                }
            }
        }
    }
}