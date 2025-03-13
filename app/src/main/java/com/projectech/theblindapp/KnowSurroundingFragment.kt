package com.projectech.theblindapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class KnowSurroundingFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.activity_know_surrounding, container, false)

        // Access TextView and set text
        val textView = view.findViewById<TextView>(R.id.textView)
        textView.text = "Hello, Know Surrounding!"

        return view
    }
}
