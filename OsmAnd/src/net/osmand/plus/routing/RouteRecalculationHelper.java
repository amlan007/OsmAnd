package net.osmand.plus.routing;

import androidx.annotation.NonNull;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.router.RouteCalculationProgress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static net.osmand.plus.notifications.OsmandNotification.NotificationType.NAVIGATION;

class RouteRecalculationHelper {

	private static final int RECALCULATE_THRESHOLD_COUNT_CAUSING_FULL_RECALCULATE = 3;
	private static final int RECALCULATE_THRESHOLD_CAUSING_FULL_RECALCULATE_INTERVAL = 2 * 60 * 1000;

	private final OsmandApplication app;
	private final RoutingHelper routingHelper;

	private final ExecutorService executor = new RouteRecalculationExecutor();
	private final Map<Future<?>, RouteRecalculationTask> tasksMap = new LinkedHashMap<>();
	private RouteRecalculationTask lastTask;

	private long lastTimeEvaluatedRoute = 0;
	private String lastRouteCalcError;
	private String lastRouteCalcErrorShort;
	private long recalculateCountInInterval = 0;
	private int evalWaitInterval = 0;

	private RouteCalculationProgressCallback progressRoute;

	RouteRecalculationHelper(@NonNull RoutingHelper routingHelper) {
		this.routingHelper = routingHelper;
		this.app = routingHelper.getApplication();
	}

	String getLastRouteCalcError() {
		return lastRouteCalcError;
	}

	String getLastRouteCalcErrorShort() {
		return lastRouteCalcErrorShort;
	}

	void setProgressBar(RouteCalculationProgressCallback progressRoute) {
		this.progressRoute = progressRoute;
	}

	boolean isRouteBeingCalculated() {
		synchronized (routingHelper) {
			for (Future<?> future : tasksMap.keySet()) {
				if (!future.isDone()) {
					return true;
				}
			}
		}
		return false;
	}

	void resetEvalWaitInterval() {
		evalWaitInterval = 0;
	}

	void stopCalculation() {
		synchronized (routingHelper) {
			for (Entry<Future<?>, RouteRecalculationTask> taskFuture : tasksMap.entrySet()) {
				taskFuture.getValue().stopCalculation();
				taskFuture.getKey().cancel(false);
			}
		}
	}

	void stopCalculationIfParamsNotChanged() {
		synchronized (routingHelper) {
			boolean hasPendingTasks = tasksMap.isEmpty();
			for (Entry<Future<?>, RouteRecalculationTask> taskFuture : tasksMap.entrySet()) {
				RouteRecalculationTask task = taskFuture.getValue();
				if (!task.isParamsChanged()) {
					taskFuture.getKey().cancel(false);
					task.stopCalculation();
				}
			}
			if (hasPendingTasks) {
				if (isFollowingMode()) {
					getVoiceRouter().announceBackOnRoute();
				}
			}
		}
	}

	private OsmandSettings getSettings() {
		return routingHelper.getSettings();
	}

	private ApplicationMode getAppMode() {
		return routingHelper.getAppMode();
	}

	private boolean isFollowingMode() {
		return routingHelper.isFollowingMode();
	}

	private VoiceRouter getVoiceRouter() {
		return routingHelper.getVoiceRouter();
	}

	private Location getLastFixedLocation() {
		return routingHelper.getLastFixedLocation();
	}

	private boolean isDeviatedFromRoute() {
		return routingHelper.isDeviatedFromRoute();
	}

	private Location getLastProjection() {
		return routingHelper.getLastProjection();
	}

	private void setNewRoute(RouteCalculationResult prevRoute, final RouteCalculationResult res, Location start) {
		final boolean newRoute = !prevRoute.isCalculated();
		if (isFollowingMode()) {
			Location lastFixedLocation = getLastFixedLocation();
			if (lastFixedLocation != null) {
				start = lastFixedLocation;
			}
			// try remove false route-recalculated prompts by checking direction to second route node
			boolean wrongMovementDirection = false;
			List<Location> routeNodes = res.getImmutableAllLocations();
			if (routeNodes != null && !routeNodes.isEmpty()) {
				int newCurrentRoute = RoutingHelperUtils.lookAheadFindMinOrthogonalDistance(start, routeNodes, res.currentRoute, 15);
				if (newCurrentRoute + 1 < routeNodes.size()) {
					// This check is valid for Online/GPX services (offline routing is aware of route direction)
					wrongMovementDirection = RoutingHelperUtils.checkWrongMovementDirection(start, routeNodes.get(newCurrentRoute + 1));
					// set/reset evalWaitInterval only if new route is in forward direction
					if (wrongMovementDirection) {
						evalWaitInterval = 3000;
					} else {
						evalWaitInterval = Math.max(3000, evalWaitInterval * 3 / 2);
						evalWaitInterval = Math.min(evalWaitInterval, 120000);
					}

				}
			}
			// trigger voice prompt only if new route is in forward direction
			// If route is in wrong direction after one more setLocation it will be recalculated
			if (!wrongMovementDirection || newRoute) {
				getVoiceRouter().newRouteIsCalculated(newRoute);
			}
		}
		app.getWaypointHelper().setNewRoute(res);
		routingHelper.newRouteCalculated(newRoute, res);
	}

	void startRouteCalculationThread(RouteCalculationParams params, boolean paramsChanged, boolean updateProgress) {
		synchronized (routingHelper) {
			getSettings().LAST_ROUTE_APPLICATION_MODE.set(getAppMode());
			RouteRecalculationTask newTask = new RouteRecalculationTask(this, params, paramsChanged);
			lastTask = newTask;
			startProgress(params);
			if (updateProgress) {
				updateProgress(params);
			}
			Future<?> future = executor.submit(newTask);
			tasksMap.put(future, newTask);
		}
	}

	public void recalculateRouteInBackground(final Location start, final LatLon end, final List<LatLon> intermediates,
											 final RouteProvider.GPXRouteParamsBuilder gpxRoute, final RouteCalculationResult previousRoute, boolean paramsChanged, boolean onlyStartPointChanged) {
		if (start == null || end == null) {
			return;
		}
		// do not evaluate very often
		if ((!isRouteBeingCalculated() && System.currentTimeMillis() - lastTimeEvaluatedRoute > evalWaitInterval)
				|| paramsChanged || !onlyStartPointChanged) {
			if (System.currentTimeMillis() - lastTimeEvaluatedRoute < RECALCULATE_THRESHOLD_CAUSING_FULL_RECALCULATE_INTERVAL) {
				recalculateCountInInterval++;
			}
			ApplicationMode mode = getAppMode();
			final RouteCalculationParams params = new RouteCalculationParams();
			params.start = start;
			params.end = end;
			params.intermediates = intermediates;
			params.gpxRoute = gpxRoute == null ? null : gpxRoute.build(app);
			params.onlyStartPointChanged = onlyStartPointChanged;
			if (recalculateCountInInterval < RECALCULATE_THRESHOLD_COUNT_CAUSING_FULL_RECALCULATE
					|| (gpxRoute != null && gpxRoute.isPassWholeRoute() && isDeviatedFromRoute())) {
				params.previousToRecalculate = previousRoute;
			} else {
				recalculateCountInInterval = 0;
			}
			params.leftSide = getSettings().DRIVING_REGION.get().leftHandDriving;
			params.fast = getSettings().FAST_ROUTE_MODE.getModeValue(mode);
			params.mode = mode;
			params.ctx = app;
			boolean updateProgress = false;
			if (params.mode.getRouteService() == RouteProvider.RouteService.OSMAND) {
				params.calculationProgress = new RouteCalculationProgress();
				updateProgress = true;
			} else {
				params.resultListener = new RouteCalculationParams.RouteCalculationResultListener() {
					@Override
					public void onRouteCalculated(RouteCalculationResult route) {
						app.runInUIThread(new Runnable() {

							@Override
							public void run() {
								finishProgress(params);
							}
						});
					}
				};
			}
			if (getLastProjection() != null) {
				params.currentLocation = getLastFixedLocation();
			}
			startRouteCalculationThread(params, paramsChanged, updateProgress);
		}
	}

	void startProgress(final RouteCalculationParams params) {
		if (params.calculationProgressCallback != null) {
			params.calculationProgressCallback.start();
		} else if (progressRoute != null) {
			progressRoute.start();
		}
	}

	void updateProgress(final RouteCalculationParams params) {
		final RouteCalculationProgressCallback progressRoute;
		if (params.calculationProgressCallback != null) {
			progressRoute = params.calculationProgressCallback;
		} else {
			progressRoute = this.progressRoute;
		}
		if (progressRoute != null) {
			app.runInUIThread(new Runnable() {

				@Override
				public void run() {
					RouteCalculationProgress calculationProgress = params.calculationProgress;
					if (isRouteBeingCalculated()) {
						if (lastTask != null && lastTask.params == params) {
							progressRoute.updateProgress((int) calculationProgress.getLinearProgress());
							if (calculationProgress.requestPrivateAccessRouting) {
								progressRoute.requestPrivateAccessRouting();
							}
							updateProgress(params);
						}
					} else {
						if (calculationProgress.requestPrivateAccessRouting) {
							progressRoute.requestPrivateAccessRouting();
						}
						progressRoute.finish();
					}
				}
			}, 300);
		}
	}

	void finishProgress(RouteCalculationParams params) {
		final RouteCalculationProgressCallback progressRoute;
		if (params.calculationProgressCallback != null) {
			progressRoute = params.calculationProgressCallback;
		} else {
			progressRoute = this.progressRoute;
		}
		if (progressRoute != null) {
			progressRoute.finish();
		}
	}

	private static class RouteRecalculationTask implements Runnable {

		private final RouteRecalculationHelper routingThreadHelper;
		private final RoutingHelper routingHelper;
		private final RouteCalculationParams params;
		private final boolean paramsChanged;

		String routeCalcError;
		String routeCalcErrorShort;
		int evalWaitInterval = 0;

		public RouteRecalculationTask(@NonNull RouteRecalculationHelper routingThreadHelper,
									  @NonNull RouteCalculationParams params, boolean paramsChanged) {
			this.routingThreadHelper = routingThreadHelper;
			this.routingHelper = routingThreadHelper.routingHelper;
			this.params = params;
			this.paramsChanged = paramsChanged;
			if (params.calculationProgress == null) {
				params.calculationProgress = new RouteCalculationProgress();
			}
		}

		public boolean isParamsChanged() {
			return paramsChanged;
		}

		public void stopCalculation() {
			params.calculationProgress.isCancelled = true;
		}

		private OsmandSettings getSettings() {
			return routingHelper.getSettings();
		}

		private void showMessage(final String msg) {
			final OsmandApplication app = routingHelper.getApplication();
			app.runInUIThread(new Runnable() {
				@Override
				public void run() {
					app.showToastMessage(msg);
				}
			});
		}

		@Override
		public void run() {
			RouteProvider provider = routingHelper.getProvider();
			OsmandSettings settings = getSettings();
			RouteCalculationResult res = provider.calculateRouteImpl(params);
			if (params.calculationProgress.isCancelled) {
				return;
			}
			final boolean onlineSourceWithoutInternet = !res.isCalculated() &&
					params.mode.getRouteService().isOnline() && !settings.isInternetConnectionAvailable();
			if (onlineSourceWithoutInternet && settings.GPX_ROUTE_CALC_OSMAND_PARTS.get()) {
				if (params.previousToRecalculate != null && params.previousToRecalculate.isCalculated()) {
					res = provider.recalculatePartOfflineRoute(res, params);
				}
			}
			RouteCalculationResult prev = routingHelper.getRoute();
			synchronized (routingHelper) {
				if (res.isCalculated()) {
					if (!params.inSnapToRoadMode && !params.inPublicTransportMode) {
						routingHelper.setRoute(res);
						routingHelper.updateOriginalRoute();
					}
					if (params.resultListener != null) {
						params.resultListener.onRouteCalculated(res);
					}
				} else {
					evalWaitInterval = Math.max(3000, routingThreadHelper.evalWaitInterval * 3 / 2); // for Issue #3899
					evalWaitInterval = Math.min(evalWaitInterval, 120000);
				}
			}
			OsmandApplication app = routingHelper.getApplication();
			if (res.isCalculated()) {
				if (!params.inSnapToRoadMode && !params.inPublicTransportMode) {
					routingThreadHelper.setNewRoute(prev, res, params.start);
				}
			} else if (onlineSourceWithoutInternet) {
				routeCalcError = app.getString(R.string.error_calculating_route)
						+ ":\n" + app.getString(R.string.internet_connection_required_for_online_route);
				routeCalcErrorShort = app.getString(R.string.error_calculating_route);
				showMessage(routeCalcError);
			} else {
				if (res.getErrorMessage() != null) {
					routeCalcError = app.getString(R.string.error_calculating_route) + ":\n" + res.getErrorMessage();
					routeCalcErrorShort = app.getString(R.string.error_calculating_route);
				} else {
					routeCalcError = app.getString(R.string.empty_route_calculated);
					routeCalcErrorShort = app.getString(R.string.empty_route_calculated);
				}
				showMessage(routeCalcError);
			}
			app.getNotificationHelper().refreshNotification(NAVIGATION);
		}
	}

	private class RouteRecalculationExecutor extends ThreadPoolExecutor {

		public RouteRecalculationExecutor() {
			super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
		}

		protected void afterExecute(Runnable r, Throwable t) {
			super.afterExecute(r, t);
			RouteRecalculationTask task = null;
			synchronized (routingHelper) {
				if (r instanceof Future<?>) {
					task = tasksMap.remove(r);
				}
			}
			if (t == null && task != null) {
				evalWaitInterval = task.evalWaitInterval;
				lastRouteCalcError = task.routeCalcError;
				lastRouteCalcErrorShort = task.routeCalcErrorShort;
			}
			lastTimeEvaluatedRoute = System.currentTimeMillis();
		}
	}
}
