package com.kuoster.gifanimationdrawable;

import android.os.Bundle;
import android.app.Activity;
import android.view.Menu;
import android.widget.ImageView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Load a gif image from resources
        GifAnimationDrawable gadAni = new GifAnimationDrawable(getResources(), R.drawable.animation);
        // Set animation drawable to the image view
        ((ImageView)findViewById(R.id.imgAniGif)).setImageDrawable(gadAni);

        // Load an interlaced gif image from resources
        GifAnimationDrawable gadAniI = new GifAnimationDrawable(getResources(), R.drawable.animation_interlaced);
        // Set animation drawable to the image view
        ((ImageView)findViewById(R.id.imgInterlacedGif)).setImageDrawable(gadAniI);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }
    
}
