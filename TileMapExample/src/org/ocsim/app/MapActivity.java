package org.ocsim.app;

import org.oscim.view.MapView;

import android.os.Bundle;
import android.view.Menu;

public class MapActivity extends org.oscim.view.MapActivity {

	private MapView mMap;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        
        mMap = (MapView) findViewById(R.id.mapView);


		// configure the MapView and activate the zoomLevel buttons
		mMap.setClickable(true);
		mMap.setFocusable(true);
		mMap.getOverlayManager().add(new EventsOverlay());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_map, menu);
        return true;
    }
}
