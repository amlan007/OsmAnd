package net.osmand.plus.views;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PointF;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.avoidroads.AvoidRoadsHelper;
import net.osmand.plus.helpers.AvoidSpecificRoads;
import net.osmand.plus.helpers.AvoidSpecificRoads.AvoidSpecificRoadsCallback;
import net.osmand.plus.views.ContextMenuLayer.ApplyMovedObjectCallback;

import java.util.List;
import java.util.Map;

public class ImpassableRoadsLayer extends OsmandMapLayer implements
		ContextMenuLayer.IContextMenuProvider, ContextMenuLayer.IMoveObjectProvider {

	private static final int START_ZOOM = 10;

	private final MapActivity activity;
	private AvoidSpecificRoads avoidSpecificRoads;
	private AvoidRoadsHelper avoidRoadsHelper;
	private ContextMenuLayer contextMenuLayer;

	private Bitmap roadWorkIcon;
	private Paint activePaint;
	private Paint paint;

	public ImpassableRoadsLayer(MapActivity activity) {
		this.activity = activity;
	}

	@Override
	public void initLayer(OsmandMapTileView view) {
		avoidSpecificRoads = activity.getMyApplication().getAvoidSpecificRoads();
		avoidRoadsHelper = activity.getMyApplication().getAvoidRoadsHelper();
		contextMenuLayer = view.getLayerByClass(ContextMenuLayer.class);
		roadWorkIcon = BitmapFactory.decodeResource(view.getResources(), R.drawable.map_pin_avoid_road);
		activePaint = new Paint();
		ColorMatrix matrix = new ColorMatrix();
		matrix.setSaturation(0);
		paint = new Paint();
		paint.setColorFilter(new ColorMatrixColorFilter(matrix));
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		if (contextMenuLayer.getMoveableObject() instanceof RouteDataObject) {
			PointF pf = contextMenuLayer.getMovableCenterPoint(tileBox);
			drawPoint(canvas, pf.x, pf.y, true);
		}
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		if (tileBox.getZoom() >= START_ZOOM) {
			for (Map.Entry<LatLon, RouteDataObject> entry : avoidSpecificRoads.getImpassableRoads().entrySet()) {
				LatLon location = entry.getKey();
				RouteDataObject road = entry.getValue();
				if (road != null && contextMenuLayer.getMoveableObject() instanceof RouteDataObject) {
					RouteDataObject object = (RouteDataObject) contextMenuLayer.getMoveableObject();
					if (object.id == road.id) {
						continue;
					}
				}
				final double latitude = location.getLatitude();
				final double longitude = location.getLongitude();
				if (tileBox.containsLatLon(latitude, longitude)) {
					drawPoint(canvas, tileBox, latitude, longitude, road != null);
				}
			}
		}

		if (tileBox.getZoom() > START_ZOOM + 1) {
			for (Map.Entry<RouteDataObject, Location> entry : avoidRoadsHelper.getRoadsToAvoid().entrySet()) {
				RouteDataObject rdo = entry.getKey();
				if (rdo != null && contextMenuLayer.getMoveableObject() instanceof RouteDataObject) {
					RouteDataObject object = (RouteDataObject) contextMenuLayer.getMoveableObject();
					if (object.id == rdo.id) {
						continue;
					}
				}
				final double lat = entry.getValue().getLatitude();
				final double lon = entry.getValue().getLongitude();
				if (tileBox.containsLatLon(lat, lon)) {
					drawPoint(canvas, tileBox, lat, lon, rdo != null);
				}
			}
		}
	}

	private void drawPoint(Canvas canvas, RotatedTileBox tileBox, double latitude, double longitude, boolean active) {
		float x = tileBox.getPixXFromLatLon(latitude, longitude);
		float y = tileBox.getPixYFromLatLon(latitude, longitude);
		drawPoint(canvas, x, y, active);
	}

	private void drawPoint(Canvas canvas, float x, float y, boolean active) {
		float left = x - roadWorkIcon.getWidth() / 2;
		float top = y - roadWorkIcon.getHeight();
		canvas.drawBitmap(roadWorkIcon, left, top, active ? activePaint : paint);
	}

	@Override
	public void destroyLayer() {

	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}

	private int getRadiusPoi(RotatedTileBox tb) {
		int r;
		if (tb.getZoom() < START_ZOOM) {
			r = 0;
		} else {
			r = 15;
		}
		return (int) (r * tb.getDensity());
	}

	private boolean calculateBelongs(int ex, int ey, int objx, int objy, int radius) {
		return Math.abs(objx - ex) <= radius && (ey - objy) <= radius / 2 && (objy - ey) <= 3 * radius;
	}

	@Override
	public boolean disableSingleTap() {
		return false;
	}

	@Override
	public boolean disableLongPressOnMap() {
		return false;
	}

	@Override
	public boolean isObjectClickable(Object o) {
		return false;
	}

	@Override
	public boolean runExclusiveAction(Object o, boolean unknownLocation) {
		return false;
	}

	@Override
	public void collectObjectsFromPoint(PointF point, RotatedTileBox tileBox, List<Object> o, boolean unknownLocation) {
		if (tileBox.getZoom() >= START_ZOOM) {
			int ex = (int) point.x;
			int ey = (int) point.y;
			int compare = getRadiusPoi(tileBox);
			int radius = compare * 3 / 2;

			for (Map.Entry<LatLon, RouteDataObject> entry : avoidSpecificRoads.getImpassableRoads().entrySet()) {
				LatLon location = entry.getKey();
				RouteDataObject road = entry.getValue();
				if (location != null && road != null) {
					int x = (int) tileBox.getPixXFromLatLon(location.getLatitude(), location.getLongitude());
					int y = (int) tileBox.getPixYFromLatLon(location.getLatitude(), location.getLongitude());
					if (calculateBelongs(ex, ey, x, y, compare)) {
						compare = radius;
						o.add(road);
					}
				}
			}

			for (Map.Entry<RouteDataObject, Location> entry : avoidRoadsHelper.getRoadsToAvoid().entrySet()) {
				LatLon location = new LatLon(entry.getValue().getLatitude(), entry.getValue().getLongitude());
				RouteDataObject road = entry.getKey();
				if (location != null && road != null) {
					int x = (int) tileBox.getPixXFromLatLon(location.getLatitude(), location.getLongitude());
					int y = (int) tileBox.getPixYFromLatLon(location.getLatitude(), location.getLongitude());
					if (calculateBelongs(ex, ey, x, y, compare)) {
						compare = radius;
						o.add(road);
					}
				}
			}
		}
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		if (o instanceof RouteDataObject) {
			return avoidSpecificRoads.getLocation((RouteDataObject) o);
		}
		return null;
	}

	@Override
	public PointDescription getObjectName(Object o) {
		if (o instanceof RouteDataObject) {
			RouteDataObject route = (RouteDataObject) o;
			return new PointDescription(PointDescription.POINT_TYPE_BLOCKED_ROAD, route.getName());
		}
		return null;
	}

	@Override
	public boolean isObjectMovable(Object o) {
		return o instanceof RouteDataObject;
	}

	@Override
	public void applyNewObjectPosition(@NonNull Object o,
									   @NonNull LatLon position,
									   @Nullable final ApplyMovedObjectCallback callback) {
		if (o instanceof RouteDataObject) {
			final RouteDataObject object = (RouteDataObject) o;
			final OsmandApplication application = activity.getMyApplication();
			application.getAvoidSpecificRoads().replaceImpassableRoad(activity, object, position, false, new AvoidSpecificRoadsCallback() {
				@Override
				public void onAddImpassableRoad(boolean success, RouteDataObject newObject) {
					if (callback != null) {
						callback.onApplyMovedObject(success, newObject);
					}
				}

				@Override
				public boolean isCancelled() {
					return callback != null && callback.isCancelled();
				}
			});
		}
	}
}
