/*
 * Copyright (c) 2017 Rod Dunne
 * All rights reserved
 * This file is subject to the terms and conditions defined in file 'LICENSE', which is part of this source code package
 */

package com.github.roddunne.mandelbrot;

import android.app.Activity;
import android.os.Bundle;


/**
 * Activity specified in the Manifest that will be started from the launcher
 */
public class MainActivity extends Activity
{
    /**
     * Lifecycle entry point.
     *
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
