package com.example.blizzard.workers;

import android.content.Context;


import android.util.Log;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;

import com.example.blizzard.data.entities.WeatherDataEntity;
import com.example.blizzard.data.repository.BlizzardRepository;
import com.example.blizzard.model.OpenWeatherService;
import com.example.blizzard.model.WeatherDataResponse;

import com.example.blizzard.util.BlizzardThread;
import com.example.blizzard.util.NotificationHelper;
import com.example.blizzard.util.TempConverter;
import com.google.common.util.concurrent.ListenableFuture;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DataUpdateWorker extends ListenableWorker {
    private static final String TAG = "DataUpdateWorker";
    private final BlizzardRepository repository;
    private Callback<WeatherDataResponse> callback;
    private BlizzardThread blizzardThread = BlizzardThread.getInstance();
    private List<WeatherDataEntity> data = new ArrayList<>();


    public DataUpdateWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        repository = new BlizzardRepository(context);

    }


    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        return CallbackToFutureAdapter.getFuture(completer -> {

            getAllFromDb();

            blizzardThread.getHandler().postDelayed(() -> {
                if (data.isEmpty()) {
                    Log.d(TAG, "doWork: No weather info in database");
                    completer.set(Result.success());
                }


                callback = new Callback<WeatherDataResponse>() {
                    @Override
                    public void onResponse(@NotNull Call<WeatherDataResponse> call, @NotNull Response<WeatherDataResponse> response) {
                        WeatherDataResponse currentWeather = response.body();
                        for (WeatherDataEntity previousWeather : data) {

                            assert currentWeather != null;
                            if (previousWeather.getCityName().equals(currentWeather.getName())) {
                                double difference = Math.abs(previousWeather.getTemperature() - Objects.requireNonNull(currentWeather).getMain().getTemp());
                                Log.d(TAG, "onResponse: Weather difference is " + difference + "°C");

                                if (difference > 2) {
                                    Log.d(TAG, "onResponse: Weather Changes detected: Notifying");
                                    NotificationHelper notificationHelper = NotificationHelper.getInstance(getApplicationContext(),
                                            previousWeather.getCityName() + ", " + previousWeather.getCountry(),
                                            TempConverter.kelToCelsius2(previousWeather.getTemperature()),
                                            TempConverter.kelToCelsius2(currentWeather.getMain().getTemp()));
                                    notificationHelper.createNotification();
                                    break;
                                }
                            }
                        }
                        completer.set(Result.success());
                    }


                    @Override
                    public void onFailure(@NotNull Call<WeatherDataResponse> call, @NotNull Throwable t) {
                        Log.e(TAG, "onFailure: Error Fetching current Weather", t);
                        completer.set(Result.failure());
                    }
                };


                Log.d(TAG, "startWork: getting data from api");
                makeNetworkRequest();

            }, 5000);

            return callback;
        });
    }

    private void makeNetworkRequest() {
        for (WeatherDataEntity entity : data){
            new OpenWeatherService().getWeather(entity.getCityName()).enqueue(callback);
        }
    }

    private void getAllFromDb() {
        AtomicReference<List<WeatherDataEntity>> weatherDataEntities = new AtomicReference<>();

        blizzardThread.getDiskIO().execute(() -> {
            weatherDataEntities.set(repository.getAllDataFromDb());
            Log.d(TAG, "getAllFromDb: done fetching");
            populate(weatherDataEntities.get());

        });
    }

    private void populate(List<WeatherDataEntity> weatherDataEntities) {
        data.addAll(weatherDataEntities);

    }
}
