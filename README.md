# firestick-mandelbrot

### Description

This is a Mandelbrot set visualization application, intended to be run on an Amazon Firestick 2.

### Motivation 

Inspired by Sverrir Berg's go example.  https://github.com/sverrirab/mandelbrot-go

Weekend effort to get a basic application onto my new Amazon Fire Stick 2.

### High Level Build Instructions

Clone the git repo.
Import the mandelbrot-firestick project into Android Studio.  
Allow Android Studio to update any files to match your local Gradle, SDK tools and Platform versions, which will most likely be newer than the source version specifies.  
Build the APK file.
Note the FULL_PATH_OF_THE_APK_FILE.apk

_These instructions were last tested with the following configuration:
Android Studio 2.3.1 Build AI-162.3871768
Platform version API 25 7.1.1
Android SDK tools 26.0.1
Gradle 3.3_

### Installation

Assumes the development machine and Amazon FireStick are on the same local subnet.

On the Amazon FireStick make sure that from the Settings you have enabled both developer options "ADB debugging" and "Allow from Unknown Sources"
From the Network settings, make a note of the IP_ADDRESS_OF_THE_FIRESTICK

From the development machine, open a command prompt in the platform-tools directory of the Android SDK.
Execute the following three commands:
adb start-server
adb connect IP_ADDRESS_OF_THE_FIRESTICK
adb install FULL_PATH_OF_THE_APK_FILE.apk

This will copy the APK file across onto the FireStick.

_These instructions were last tested with the following configuration:
Android Debug Bridge version 1.0.39_

### Usage

The application requires a BlueTooth mouse be paired to the FireStick.
This should provide a white circle (pseudo-touch) cursor.

Launch the Mandelbrot application from the FireStick launcher using the FireStick remote.  It can be found in the Your Applications sub-page.

Clicking the mouse will zoom the view, by a factor of two, re-centered at the cursor location.
The application permits zooming in approximately fifty times, before it runs out of precision.

### Known Issues

The application is hard-coded to TVs (Android devices) running landscape orientation at 1920 x 1080 resolution.

The application does not utilize the FireStick remote for navigation.

### Future Improvements

##### Horrendous Performance

The FireStick is almost a hundred times slower than an Android emulator running on a 3.3GHz Intel i5.  All of the time is being spent in the basic calculation loops, i.e. floating point unit performance.

The application uses only one worker thread, whereas the FireStick 2 has four cores that could be used.  Note the MT8173 has mismatched cores, so two are faster.

We need to investigate whether floats would be possibly faster than doubles, without introducing too much imprecision.


### Credits

The basic mathematical equation and Escape Time Algorithm are described on the Wikipedia page https://en.wikipedia.org/wiki/Mandelbrot_set.

Periodicity checking and border edge checking (the more advanced version of interpolation) are also described, in brief, there.

### License

MIT license. 
