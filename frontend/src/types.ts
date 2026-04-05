export type RoutePoint = {
  lat: number;
  lng: number;
};

export type Sample = {
  timeSec: number;
  distance: number;
  speed: number;
  heartRate: number;
  lat: number;
  lng: number;
  runningCadence: number;
  strideLength: number;
  power: number;
  altitude: number;
};

export type PreviewResponse = {
  totalDistanceMeters: number;
  totalDurationSec: number;
  calories: number;
  samples: Sample[];
};
