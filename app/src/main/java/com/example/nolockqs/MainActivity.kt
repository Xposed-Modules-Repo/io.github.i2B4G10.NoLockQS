package com.example.nolockqs

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.nolockqs.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        updateModuleStatus()
    }

    private fun updateModuleStatus() {
        if (isModuleActive()) {
            binding.statusValue.text = getString(R.string.status_active)
            binding.statusValue.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            binding.statusValue.text = getString(R.string.status_inactive)
            binding.statusValue.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun isModuleActive(): Boolean {
        // The Xposed module hooks this method to return true.
        return false
    }
}
