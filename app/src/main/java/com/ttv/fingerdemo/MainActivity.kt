package com.ttv.fingerdemo

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import com.ttv.palm.*
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private val permissionsDelegate = PermissionsDelegate(this)
    private var hasPermission: Boolean = false;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val ret = PalmEngine.createInstance(this).init()
        Log.e("TestEngine", "activation: " + ret);
        if(ret != 0) {
            findViewById<TextView>(R.id.txtState).text = "No Activated!"
        }

        val btnRegister = findViewById<Button>(R.id.btnRegister)
        btnRegister.setOnClickListener {
            val intent = Intent(this, FingerCaptureActivity::class.java)
            intent.putExtra("mode", 0);
            startActivityForResult(intent, 1)
        }

        val btnVerify = findViewById<Button>(R.id.btnVerify)
        btnVerify.setOnClickListener {
            val intent = Intent(this, FingerCaptureActivity::class.java)
            intent.putExtra("mode", 1);
            startActivityForResult(intent, 2)
        }

        val btnWriterRegister = findViewById<Button>(R.id.btnWriterRegister)
        btnWriterRegister.setOnClickListener {
            val intent = Intent(this, FingerCaptureActivity::class.java)
            intent.putExtra("mode", 2);
            startActivityForResult(intent, 1)
        }

        val btnWriterVerify = findViewById<Button>(R.id.btnWriterVerify)
        btnWriterVerify.setOnClickListener {
            val intent = Intent(this, FingerCaptureActivity::class.java)
            intent.putExtra("mode", 3);
            startActivityForResult(intent, 2)
        }

        hasPermission = permissionsDelegate.hasPermissions()
        if (!hasPermission) {
            permissionsDelegate.requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionsDelegate.hasPermissions() && !hasPermission) {
            hasPermission = true
        } else {
            permissionsDelegate.requestPermissions()
        }
    }

    private fun checkPermission(): Boolean {
        return if (SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val result =
                ContextCompat.checkSelfPermission(this@MainActivity, READ_EXTERNAL_STORAGE)
            val result1 =
                ContextCompat.checkSelfPermission(this@MainActivity, WRITE_EXTERNAL_STORAGE)
            result == PackageManager.PERMISSION_GRANTED && result1 == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        if (SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivityForResult(intent, 2296)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivityForResult(intent, 2296)
            }
        } else {
            //below android 11
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(WRITE_EXTERNAL_STORAGE),
                2296
            )
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val registerID = data!!.getStringExtra("registerID")
            Toast.makeText(this, "Register succeed! " + registerID, Toast.LENGTH_SHORT).show()
        } else if(requestCode == 2 && resultCode == RESULT_OK) {
            val verifyResult = data!!.getIntExtra ("verifyResult", 0)
            val verifyID = data!!.getStringExtra("verifyID");
            if(verifyResult == 1) {
                val verified = "Verify succeed! ID: " + verifyID;
                Toast.makeText(this, verified, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Verify failed!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}