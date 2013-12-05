Android Cache Library
=====================

A general purpose utility library for caching resources for Android apps.

Installation
------------

**build.gradle**

	repositories {
		mavenCentral()
	}

	dependencies {
	  compile 'com.vandalsoftware.android:cache-library:1.0.+@aar'
	}

Features
--------

* DiskLruCache 2.0: A refactor of AOSP
 [DiskLruCache](http://developer.android.com/training/displaying-bitmaps/cache-bitmap.html).
 Marginal improvement to reading and writing journal files.
