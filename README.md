# WakeScience

A scientifically grounded, standalone Smart Wake & Sleep Staging Engine for Wear OS (Samsung Galaxy Watch 4/5/6/7) with bidirectional Sleep as Android integration.

## Architecture

```text
               +-------------------------------------------------+
               |              Sleep as Android (Phone)           |
               |  - Schedules alarms & sets bedtime targets       |
               |  - Fires SMART_PERIOD_AUTO 30m before deadline  |
               +-----------------------+-------------------------+
                                       | (Android Intent)
                                       v
                        +------------------------------+
                        |      :app (Phone Bridge)     |
                        |  SleepAsAndroidReceiver      |
                        +--------------+---------------+
                                       | (Wearable Message Client)
                                       v
               +-------------------------------------------------+
               |             :wear (Galaxy Watch App)            |
               |  WearTrackingService (health foreground service)|
               |  - Immune to Samsung Knox / Doze task killing    |
               |  - Samples PPG Heart Rate (BPM & IBI / RR)      |
               |  - Samples 3-Axis Accelerometer (Vector Mag)    |
               +-----------------------+-------------------------+
                                       |
                                       v
               +-------------------------------------------------+
               |                 :core-science                   |
               |  SleepStageClassifier:                          |
               |    - Cole-Kripke Actigraphy Function            |
               |    - Autonomic Nervous System RMSSD / HRV       |
               |  SmartWakeEngine:                               |
               |    - Suppresses alarms during N3 Slow-Wave      |
               |    - Fires on Light Sleep (N1/N2) Micro-Arousal |
               |  StimulusRampController:                        |
               |    - Logarithmic Weber-Fechner acoustic ramp    |
               |    - Rhythmic expanding wrist haptic duty-cycle |
               +-------------------------------------------------+
```

## Scientific Principles Implemented

1. **Sleep Inertia Mitigation**:
   - Awakening from **N3 Slow-Wave Deep Sleep** results in severe cognitive deficits (up to 41% immediate reduction in cognitive performance, lasting up to 2 hours) (*Tassi et al., 2000; Hilditch et al., 2019*).
   - `SmartWakeEngine` explicitly suppresses waking stimuli during verified N3 epochs.

2. **Physiological Signal Discrimination**:
   - Deep sleep is characterized by strong parasympathetic (cardiovagal) tone, marked by high RMSSD and low baseline resting heart rate.
   - Light sleep (N1/N2) and spontaneous arousals present transient sympathetic surges (HR spikes > 8 bpm above baseline) accompanied by micro-movements.
   - Both signals must align to declare an "Easy Wake" state.

3. **Auditory & Haptic Arousal Threshold Dynamics**:
   - Follows *Williams et al. (1964)* and *McFarlane et al. (PLoS ONE 2020)*.
   - Stimulus starts at near-zero perception and follows a 5-minute logarithmic curve, allowing natural surfacing without startling or cortisol spikes.
