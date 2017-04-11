/*
 * Copyright (c) 2017 Rod Dunne
 * All rights reserved
 * This file is subject to the terms and conditions defined in file 'LICENSE', which is part of this source code package
 */

package com.github.roddunne.mandelbrot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;


/**
 * 2017-04-02 rdunne
 *
 * This view class manually draws a Mandelbrot set, using basic Canvas bitmap operations, at a fixed expected
 * resolution of 1920x1080.  This matches the default resolution of the Amazon FireStick 2 on my TV.
 *
 * The starting image will display the "usual" x-axis bulb and cardioid entire set.  The colorization uses the
 * simple escape time count approach and does not smooth the colors.
 *
 * The user can then zoom in, by a factor of two, by clicking the primary button of an attached BlueTooth mouse on the
 * FireStick.
 *
 */
public class MandelbrotView extends View
{
    /******************************************************************************************************************/
    // Magic numbers

    // Number of colors in the palette for iteration to color mapping
    private final static int paletteSize_ = 256;

    // Assume only default TV resolution for now.
    private final static int screenHeight_ = 1080;
    private final static int screenWidth_ = 1920;

    // Starting range of the real/imaginary axes on the screen.
    private final static double startingMinimumRealRange_ = -4.0;
    private final static double startingMaximumRealRange_ = 2.6;
    private final static double startingMinimumImaginaryRange_ = -1.8;
    private final static double startingMaximumImaginaryRange_ = 1.8;

    /******************************************************************************************************************/
    // Algorithm tuning

    // Configurable variables for the algorithm iteration
    private final static int maximumTestIterations_ = 512;
    private final static double escapeValueSquared_ = 4.0;

    /******************************************************************************************************************/
    // Run-time algorithm data

    private double minimumRealRange_ = startingMinimumRealRange_;
    private double maximumRealRange_ = startingMaximumRealRange_;
    private double minimumImaginaryRange_ = startingMinimumImaginaryRange_;
    private double maximumImaginaryRange_ = startingMaximumImaginaryRange_;

    /******************************************************************************************************************/
    // Drawing data

    // Color to represent being inside the set
    private final Paint paintBlack_ = new Paint();
    // Colors used to represent the iterations required to escape the set
    private final List<Paint> paintArray_ = new ArrayList<Paint>(paletteSize_);

    // The actual bitmap that gets drawn onto the screen
    private Bitmap renderBitmap_ = Bitmap.createBitmap(screenWidth_, screenHeight_, Bitmap.Config.ARGB_8888);
    // The paint used to draw onto the onscreen canvas, passed to us in onDraw
    private final Paint canvasPaint = new Paint(Paint.DITHER_FLAG);

    // Bitmaps at various scales, used to improve user experience by simple progressive rendering  and displaying (interlacing)
    private final Bitmap bitmapFull_ = Bitmap.createBitmap(screenWidth_, screenHeight_, Bitmap.Config.ARGB_8888);
    private final Bitmap bitmapHalf_ = Bitmap.createBitmap(screenWidth_ / 2, screenHeight_ / 2, Bitmap.Config.ARGB_8888);
    private final Bitmap bitmapQuarter_ = Bitmap.createBitmap(screenWidth_ / 4, screenHeight_ / 4, Bitmap.Config.ARGB_8888);
    private final Bitmap bitmapEighth_ = Bitmap.createBitmap(screenWidth_ / 8, screenHeight_ / 8, Bitmap.Config.ARGB_8888);

    // A container of the iteration counts calculated for the current "zoom" level
    private final int[][] iterationArray_ = new int[screenWidth_][screenHeight_];

    /******************************************************************************************************************/
    // Job, threading and state run time data

    // The current zoom level of the Mandelbrot set, incremented by the user mouse clicks
    private int currentZoomLevel_ = 0;
    // The latest zoom level that we have actually drawn on screen, used to "catch up" when the user clicks rapidly in succession.
    private int actualRenderedZoomLevel_ = 0;

    // A list of level creation and rendering jobs to perform in FIFO order
    private final ConcurrentLinkedDeque<CreationJob> creationJobs_ = new ConcurrentLinkedDeque<CreationJob>();


    /**
     * Constructor
     *
     * @param context The application Context
     */
    public MandelbrotView(Context context)
    {
        super(context);
        initialize();
    }


    /**
     * Constructor
     *
     * @param context The application Context
     * @param attrs Style information for custom views
     */
    public MandelbrotView(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        initialize();
    }


    /**
     * Constructor
     *
     * @param context The application Context
     * @param attrs Style information for custom views
     * @param defStyle Style information for custom views
     */
    public MandelbrotView(Context context, AttributeSet attrs, int defStyle)
    {
        super(context, attrs, defStyle);
        initialize();
    }


    /**
     * Common initializer
     *
     * Set up the palette.
     */
    private void initialize()
    {
        // TODO - Use this to seed the number of worker threads in a ThreadPoolExecutor
        int numberOfProcessors = Runtime.getRuntime().availableProcessors();

        paintBlack_.setColor(Color.BLACK);

        // Even though not hard-coded, assumes palette is 256 colors.
        for (int n = 0; n < paletteSize_; ++n)
        {
            Paint paint = new Paint();
            paint.setColor(Color.HSVToColor(new float[]{n % 256, 255, 255}));
            paintArray_.add(paint);
        }

        // Make the grid something nonsensical, so the interpolation algorithm does not match erroneously on first run.
        for (int gridY = 0; gridY < screenHeight_; ++gridY)
        {
            for (int gridX = 0; gridX < screenWidth_; ++gridX)
            {
                iterationArray_[gridX][gridY] = -1;
            }
        }

        // Start "task manager" busy-wait
        runJobs();

        // Create the first level
        updateZoomLevel();
    }


    /**
     * Uses a busy-wait synchronized queue of job objects to execute off the UI thread in order to
     * calculate each level of zoom into the Mandelbrot set, and then render that new level into various
     * scaled off screen bitmaps.  These bitmaps are then copied on screen during on draw.
     *
     * TODO - switch all this to use Androids built in ThreadPoolExecutor with FutureTasks for callbacks.
     * The FireStick has 4 cores.  One last optimization would be to use more of them and to allow the OS
     * to manage the blocking waits.
     */
    private void runJobs()
    {
        new Thread(new Runnable()
        {
            public void run()
            {
                // Enter endless busy-wait
                while (true)
                {
                    // If we have a job, pull it and run it
                    if ( ! creationJobs_.isEmpty())
                    {
                        CreationJob nextJob = creationJobs_.removeFirst();
                        // Only calculate if the user has not clicked the mouse since job creation
                        if (nextJob.zoomLevelAtJobCreation_ >= currentZoomLevel_)
                        {
                            nextJob.doLongJob();
                            // Only render if the user has not clicked the mouse since job creation
                            if (nextJob.zoomLevelAtJobCreation_ >= currentZoomLevel_)
                            {
                                actualRenderedZoomLevel_ = nextJob.zoomLevelAtJobCreation_;
                                nextJob.doPostJob();
                            }
                            // Or render if we haven't rendered this level
                            else if (nextJob.zoomLevelAtJobCreation_ > actualRenderedZoomLevel_)
                            {
                                actualRenderedZoomLevel_ = nextJob.zoomLevelAtJobCreation_;
                                nextJob.doPostJob();
                            }
                        }
                    }
                }

            }
        }).start();
    }


    /**
     * Called to zoom into the next level of the Mandelbrot set
     *
     * TODO - At first this code was all procedural with many lines of copy/paste
     * Then it was refactored into the table driven code (using the arrays of pairs)
     * Then the job sub-classes were added to wrap those.
     * But the class hierarchy is unnecessary, a single level could be parameterized sufficiently
     * to reduce all this code, without it being unreadable.  Even taking into account the special
     * behaviour of the full scale calculation i.e. interpolating if possible.
     */
    private void updateZoomLevel()
    {
        // Erase the iteration count container, so interpolations are not erroneously based on previous level.
        for (int gridY = 0; gridY < screenHeight_; ++gridY)
        {
            for (int gridX = 0; gridX < screenWidth_; ++gridX)
            {
                iterationArray_[gridX][gridY] = -1;
            }
        }

        // Create jobs to calculate and render the new zoom level at one eighth, quarter half and full size.
        // NOTE - order is important so the progressive rendering appears correct/optimum i.e. it's a FIFO
        CreationJob eighthJob = new EightCreationJob(currentZoomLevel_);
        creationJobs_.addLast(eighthJob);

        CreationJob quarterJob = new QuarterCreationJob(currentZoomLevel_);
        creationJobs_.addLast(quarterJob);

        CreationJob halfJob = new HalfScaleCreationJob(currentZoomLevel_);
        creationJobs_.addLast(halfJob);

        CreationJob fullJob = new FullScaleCreationJob(currentZoomLevel_);
        creationJobs_.addLast(fullJob);
    }


    /**
     * Inner helper base class to calculate and render a single bitmap for a new level of the Mandelbrot set.
     *
     * NOTE these classes make use of the outer class bitmap data.
     */
    private abstract class CreationJob
    {
        // The zoom level at the time the job was created.  may change before execution.
        protected final int zoomLevelAtJobCreation_;
        // The bitmap to draw into
        protected Bitmap renderBitmapForJob_;
        // The progressive rendering/"interlace"/scaling factor to draw at
        protected int factor_;

        /**
         * Constructor
         * @param currentZoomLevel The zoom level at the time the job was created.
         */
        CreationJob(int currentZoomLevel)
        {
            zoomLevelAtJobCreation_ = currentZoomLevel;
        }

        // Defer calculation to sub-classes
        abstract public void doLongJob();

        // Rendering is the same for all sub-classes, actual drawing needs to be done on the UI thread though.
        public void doPostJob()
        {
            renderBitmap_ = Bitmap.createScaledBitmap(renderBitmapForJob_, screenWidth_, screenHeight_, true);
            // post stuff to UI thread on outer class.
            post(new Runnable()
            {
                public void run()
                {
                    // Simply request a redraw
                    invalidate();
                }
            });
        }
    }


    /**
     * Used to calculate the full scale iteration count for a level of the Mandelbrot set
     *
     * NOTE - assumes the other levels have already been calculated as it skips those array coordinates
     * NOTE - will try to interpolate once it has valid values for a grid location's four neighbours
     */
    private class FullScaleCreationJob extends CreationJob
    {
        // Tables of starting points for calculating subsets of the Mandelbrot set
        private final int[] fullScaleCreationStartingPairs_ = { 1,1, 3,1, 5,1, 7,1, 1,3, 3,3, 5,3, 7,3, 1,5, 3,5, 5,5, 7,5, 1,7, 3,7, 5,7, 7,7 };
        private final int[] fullScaleInterpolatedCreationStartingPairs_ = { 1,0, 3,0, 5,0, 7,0, 0,1, 2,1, 4,1, 6,1, 1,2, 3,2, 5,2, 7,2, 0,3, 2,3, 4,3, 6,3,
                1,4, 3,4, 5,4, 7,4, 0,5, 2,5, 4,5, 6,5, 1,6, 3,6, 5,6, 7,6, 0,7, 2,7, 4,7, 6,7};

        /**
         * Constructor
         *
         * @param currentZoomLevel The zoom level when this job was created.
         */
        FullScaleCreationJob(int currentZoomLevel)
        {
            super(currentZoomLevel);
            renderBitmapForJob_ = bitmapFull_;
            factor_ = 1;
        }

        /**
         * Call the Mandelbrot subset creation function repeatedly until the iteration count array has been
         * completely calculated for the full scale at this zoom level.
         *
         * NOTE these calculations can be interrupted by the user zoom.
         *
         * Intended to be called from a background thread
         */
        @Override
        public void doLongJob()
        {
            for (int index = 0; index < fullScaleCreationStartingPairs_.length && (currentZoomLevel_ == zoomLevelAtJobCreation_); index += 2)
            {
                createMandelbrotSubset(fullScaleCreationStartingPairs_[index], fullScaleCreationStartingPairs_[index + 1], zoomLevelAtJobCreation_, true, false);
            }

            for (int index = 0; index < fullScaleInterpolatedCreationStartingPairs_.length && (currentZoomLevel_ == zoomLevelAtJobCreation_); index += 2)
            {
                // Permit interpolation.
                createMandelbrotSubset(fullScaleInterpolatedCreationStartingPairs_[index], fullScaleInterpolatedCreationStartingPairs_[index + 1], zoomLevelAtJobCreation_, true, true);
            }

            if (zoomLevelAtJobCreation_ >= currentZoomLevel_)
            {
                renderLevelByStep(renderBitmapForJob_, factor_);
            }
        }
    }


    /**
     * Used to calculate the half scale iteration count for a level of the Mandelbrot set
     *
     * NOTE - assumes the lower levels have already been calculated as it skips those array coordinates
     */
    private class HalfScaleCreationJob extends CreationJob
    {
        // Tables of starting points for calculating subsets of the Mandelbrot set
        private final int[] halfScaleCreationStartingPairs_ = { 2,0,  6,0,  0,2,  2,2,  4,2,  6,2,  2,4,  6,4,  0,6,  2,6,  4,6,  6,6 };

        /**
         * Constructor
         *
         * @param currentZoomLevel The zoom level when this job was created.
         */
        HalfScaleCreationJob(int currentZoomLevel)
        {
            super(currentZoomLevel);
            renderBitmapForJob_ = bitmapHalf_;
            factor_ = 2;
        }

        /**
         * Call the Mandelbrot subset creation function repeatedly until the iteration count array has been
         * completely calculated for the half scale at this zoom level.
         *
         * NOTE these calculations can be interrupted by the user zoom.
         *
         * Intended to be called from a background thread
         */
        @Override
        public void doLongJob()
        {
            for (int index = 0; index < halfScaleCreationStartingPairs_.length && (currentZoomLevel_ == zoomLevelAtJobCreation_); index += 2)
            {
                createMandelbrotSubset(halfScaleCreationStartingPairs_[index], halfScaleCreationStartingPairs_[index + 1], zoomLevelAtJobCreation_, true, false);
            }

            if (zoomLevelAtJobCreation_ >= currentZoomLevel_)
            {
                renderLevelByStep(renderBitmapForJob_, factor_);
            }
        }
    }


    /**
     * Used to calculate the quarter scale iteration count for a level of the Mandelbrot set
     *
     * NOTE - assumes the eighth level has already been calculated as it skips those array coordinates
     */
    private class QuarterCreationJob extends CreationJob
    {
        // Tables of starting points for calculating subsets of the Mandelbrot set
        private final int[] level4CreationStartingPairs_ = { 4,0,  4,4,  0,4};

        /**
         * Constructor
         *
         * @param currentZoomLevel The zoom level when this job was created.
         */
        QuarterCreationJob(int currentZoomLevel)
        {
            super(currentZoomLevel);
            renderBitmapForJob_ = bitmapQuarter_;
            factor_ = 4;
        }

        /**
         * Call the Mandelbrot subset creation function repeatedly until the iteration count array has been
         * completely calculated for the quarter scale at this zoom level.
         *
         * NOTE these calculations can be interrupted by the user zoom.
         *
         * Intended to be called from a background thread
         */
        @Override
        public void doLongJob()
        {
            for (int index = 0; index < level4CreationStartingPairs_.length && (currentZoomLevel_ == zoomLevelAtJobCreation_); index += 2)
            {
                createMandelbrotSubset(level4CreationStartingPairs_[index], level4CreationStartingPairs_[index + 1], zoomLevelAtJobCreation_, true, false);
            }

            if (zoomLevelAtJobCreation_ >= currentZoomLevel_)
            {
                renderLevelByStep(renderBitmapForJob_, factor_);
            }
        }
    }


    /**
     * Used to calculate the eighth scale iteration count for a level of the Mandelbrot set
     */
    private class EightCreationJob extends CreationJob
    {
        // Tables of starting points for calculating a subset of the Mandelbrot set
        private final int[] level8CreationStartingPairs_ = { 0,0 };

        /**
         * Constructor
         *
         * @param currentZoomLevel The zoom level when this job was created.
         */
        EightCreationJob(int currentZoomLevel)
        {
            super(currentZoomLevel);
            renderBitmapForJob_ = bitmapEighth_;
            factor_ = 8;
        }

        /**
         * Call the Mandelbrot subset creation function repeatedly until the iteration count array has been
         * completely calculated for the full scale at this zoom level.
         *
         * NOTE these calculations CANNOT be interrupted by the user zoom.
         *
         * Intended to be called from a background thread
         */
        @Override
        public void doLongJob()
        {
            for (int index = 0; index < level8CreationStartingPairs_.length; index +=2)
            {
                // Always calculate the eighth scale, once you have started, don't check for user interruption
                createMandelbrotSubset(level8CreationStartingPairs_[index], level8CreationStartingPairs_[index + 1], zoomLevelAtJobCreation_, false, false);
            }

            // Always render the eighth scale, once you have started, don't check for user interruption
            renderLevelByStep(renderBitmapForJob_, factor_);
        }
    }


    /**
     * Used to create a portion of the Mandelbrot set for the current zoom level.
     *
     * It is expected to be called repeatedly to calculate all the values for a single level.
     *
     * It skips across cells in the iteration count array, calculating whether they are in or out
     * of the Mandelbrot set.  And then setting their iteration count value to reflect this.
     * These iteration counts can then be used to create bitmap image.
     *
     * The purpose of splitting the calculation into multiple steps is
     * 1) to allow us to display a one eighth, one quarter and one half version of each zoom level to the user
     * as soon as the values have been calculated.
     * 2) to reuse the values from the eighth, quarter, and half levels in the final full scale version.  i.e.  to
     * not recalculate each scale individually.
     *
     * NOTE with an naive full calculation algorithm, the time from a user zoom to actual display on the Android TV
     * API 25 emulator, with two vcores on a 3GHz Intel i5, with an escape iteration maximum of 1024 and an escape value of 64.0
     * was about 15 seconds.
     * On an actual FireStick 2, it was ~700 seconds!  With all the optimizations, progressive rendering, interpolation,
     * periodicity, this has been reduced to a still ridiculous ~100 seconds.  The current hard coded values of 512 and 2.0
     * result in a rendering of the final full scale image in about 30 seconds on the FireStick (and 2 seconds on emulator).
     *
     * Interpolation optimization.  If a pixel is surrounded by the same color on all four sides, we can fill it with the
     * same color.  We did measure that this was a noticeable performance increase.  We did not measure the error rate though.
     *
     * NOTE at present these errors are probably OK as we do not resue the previous level to seed any of the values in
     * the next zoom level.  However when we introduce that optimization we will need to actually calculate any interpolated
     * points.
     *
     * The extension of this optimization is the area filling optimization that attempts to subset
     * the image into rectangular areas and if all four sides have the same value, they fill the rectangle with that value.
     *
     * NOTE Interpolating is only possible at the finest level.  It also depends on the ordering
     * of the previous calls to this function, to ensure that the cells on all four sides already have values
     * assigned to them. i.e The order of pairs in the table driven arrays for starting coordinates matters.
     *
     * NOTE The choice of "escape" value, typically 2.0, and the maximum number of iterations to
     * try before assuming the point is in the Mandelbrot set both have a determination on the "fineness" of the
     * image.  But, increasing them increases both fineness and runtime.
     *
     * Periodicity optimization.  If a cell is in the set, the test will iterate to the maximum without the values escaping.
     * But often the values enter a repeating cycle long before they reach the maximum iteration count.  Testing for these
     * cycles does actually save iterations.
     *
     * NOTE The check for periodicity usually first tests the immediate neighbour to see whether to bother with
     * a periodicity test at all.  This optimization is intended to avoid unnecessary calculations.
     * In our case, as we construct the set in jumps of eight pixels, this test would be harder.  At present we simply use
     * the last pixel created, which is off by seven.  This will not be as optimal.  But runtime metrics showed that we
     * still avoid many calculations.
     *
     * TODO rethink to remove the state parameters and the overall function complexity
     *
     * @param startX The starting x offset into the iteration count array
     * @param startY The starting y offset into the iteration count array
     * @param startingZoomLevel The zoom level at the time when the calculation job was created.
     * @param checkForAbort Whether we check for user interruption to stop calculating.
     */
    private void createMandelbrotSubset(final int startX, final int startY, final int startingZoomLevel, final boolean checkForAbort, final boolean useInterpolation)
    {
        final int step = 8;
        final double realPixelIncrement = (maximumRealRange_ - minimumRealRange_) / (screenWidth_ - 1);
        final double imaginaryPixelIncrement = (maximumImaginaryRange_ - minimumImaginaryRange_) / (screenHeight_ - 1);

        // Used to allow early aborting of the algorithm, when the calculations are no longer needed due to user zooming.
        boolean userInterruption = false;

        for (int gridY = startY; gridY < screenHeight_ && ! userInterruption; gridY += step)
        {
            // The value on the imaginary axis for this pixel
            final double currentImaginary = maximumImaginaryRange_ - gridY * imaginaryPixelIncrement;

            // Used to optimize periodicity tests.
            boolean previousPixelWasInsideTheSet = false;

            for (int gridX = startX; gridX < screenWidth_; gridX += step)
            {
                // Possible value interpolation for this cell in the iteration count array
                boolean skipDueToInterpolating = false;

                // At the finest level, i.e. when all the other iteration count calculations are complete, it is an optimization to
                // interpolate the values for pixels where all four neighbours are the same color
                if (useInterpolation)
                {
                    // If not the top or bottom row
                    if (gridY > 0 && gridY < screenHeight_ - 1)
                    {
                        // If not the left or right column
                        if (gridX > 0 && gridX < screenWidth_ - 1)
                        {
                            final int left = iterationArray_[gridX - 1][gridY];
                            final int right = iterationArray_[gridX + 1][gridY];
                            if (left == right)
                            {
                                final int above = iterationArray_[gridX][gridY - 1];
                                if (above == left)
                                {
                                    final int below = iterationArray_[gridX][gridY + 1];
                                    if (below == left)
                                    {
                                        // If cells on all four sides are the same, assume this one is the same
                                        iterationArray_[gridX][gridY] = left;
                                        skipDueToInterpolating = true;
                                    }
                                }
                            }
                        }
                    }
                }

                if (!skipDueToInterpolating)
                {
                    // The value on the real axis for this pixel
                    final double currentReal = minimumRealRange_ + gridX * realPixelIncrement;

                    // Starting values for the "Mandelbrot set member" test algorithm
                    double realZ = currentReal;
                    double imaginaryZ = currentImaginary;
                    boolean insideTheSet = true;
                    int iterations = 0;

                    // Starting values for the periodicity tests for this point
                    double realPeriodicityTestValue = 0.0;
                    double imaginaryPeriodicityTestValue = 0.0;
                    int currentPeriodicityTestCount = 0;
                    int maximumAttemptsToFindRepeats = 1;

                    // For this pixel/point, iterate over the mapped real and imaginary values, until either the
                    // values "escape" or the pixel is assumed to be in the Mandelbrot set.
                    for (iterations = 0; iterations < maximumTestIterations_; ++iterations)
                    {
                        // Use minimum multiplications per test iteration
                        final double realZSquared = realZ * realZ;
                        final double imaginaryZSquared = imaginaryZ * imaginaryZ;
                        if (realZSquared + imaginaryZSquared > escapeValueSquared_)
                        {
                            insideTheSet = false;
                            break;
                        }

                        // Calculate next test values
                        imaginaryZ = 2 * realZ * imaginaryZ + currentImaginary;
                        realZ = realZSquared - imaginaryZSquared + currentReal;

                        // Only bother to test a pixel/cell for periodicity if the previous one was in the set.
                        if (previousPixelWasInsideTheSet)
                        {
                            // NOTE comparison operator on floating point values actually works adequately here.
                            if (realZ == realPeriodicityTestValue)
                            {
                                if (imaginaryZ == imaginaryPeriodicityTestValue)
                                {
                                    iterations = maximumTestIterations_;
                                    break;
                                }
                            }

                            currentPeriodicityTestCount++;

                            // If we believe that this pixel is likely be in the set, and we haven't found values that repeat yet,
                            // then double the number of steps before resetting the test values.
                            if (currentPeriodicityTestCount > maximumAttemptsToFindRepeats)
                            {
                                currentPeriodicityTestCount = 0;
                                maximumAttemptsToFindRepeats *= 2;
                                realPeriodicityTestValue = realZ;
                                imaginaryPeriodicityTestValue = imaginaryZ;
                            }
                        }
                    }

                    // Set the iteration count array value for this point.
                    if (insideTheSet)
                    {
                        previousPixelWasInsideTheSet = true;
                        iterationArray_[gridX][gridY] = -1;
                    }
                    else
                    {
                        previousPixelWasInsideTheSet = false;
                        iterations = iterations < maximumTestIterations_ ? iterations : maximumTestIterations_;
                        iterationArray_[gridX][gridY] = iterations;
                    }
                }

                // If the user has "clicked the mouse" the current zoom will have changed, maybe we no longer
                // need to keep calculating.
                if (checkForAbort && (currentZoomLevel_ > startingZoomLevel))
                {
                    userInterruption = true;
                }
            }
        }
    }


    /**
     * Renders a level of the Mandelbrot set, into a bitmap, using the current iteration count array
     *
     * It does this using a "step".  The step is equivalent to the zoom/progressive rendering factor.
     * So, a step of eight would render each eighth pixel.  The resulting bitmap would then
     * be expected to be scaled up to the screen size.
     *
     * The color for each pixel is based on the mapping from the iteration count to a color palette.
     * Pixels that are inside the set are colored black.
     *
     * @param offscreenBitmap The bitmap to draw into, needs to match the step size.
     * @param step The step to use when iterating across and down the iteration count array.
     */
    private void renderLevelByStep(Bitmap offscreenBitmap, int step)
    {
        final Canvas offscreenCanvas = new Canvas(offscreenBitmap);
        final int bitmapWidth = offscreenBitmap.getWidth();
        final int bitmapHeight = offscreenBitmap.getHeight();

        for (int bitmapY = 0; bitmapY < bitmapHeight; ++bitmapY)
        {
            final int gridY = bitmapY * step;
            for (int bitmapX = 0; bitmapX < bitmapWidth; ++bitmapX)
            {
                final int gridX = bitmapX * step;
                int iterations = iterationArray_[gridX][gridY];
                if (iterations == -1)
                {
                    offscreenCanvas.drawPoint(bitmapX, bitmapY, paintBlack_);
                }
                else
                {
                    // TODO - ensure the iteration count array never stores more than maximum expected iterations.
                    iterations = iterations < maximumTestIterations_ ? iterations : maximumTestIterations_;
                    int index = iterations % paletteSize_;
                    offscreenCanvas.drawPoint(bitmapX, bitmapY, paintArray_.get(index));
                }
            }
        }
    }


    /**
     * Called from the framework to redraw our view.
     *
     * @param canvas The canvas we are to draw into
     */
    @Override
    protected void onDraw(Canvas canvas)
    {
        // Simply copy over the latest bitmap that we have rendered off screen.
        canvas.drawBitmap(renderBitmap_, 0, 0, canvasPaint);
    }


    /**
     * Called when the user performs a mouse/touch action inside the Mandelbrot view.
     *
     * We respond to a "click" by "zooming" the Mandelbrot set by a factor of two at the mouse/touch point.
     *
     * @param e The event that occurred
     * @return True if we handled the event.
     */
    @Override
    public boolean onTouchEvent(MotionEvent e)
    {
        final float touchX = e.getX();
        final float touchY = e.getY();
        switch (e.getAction())
        {
            case MotionEvent.ACTION_DOWN:
                // Find the real and imaginary value at this pixel
                double realPixelIncrement = (maximumRealRange_ - minimumRealRange_) / (screenWidth_ - 1);
                double imaginaryPixelIncrement = (maximumImaginaryRange_ - minimumImaginaryRange_) / (screenHeight_ - 1);
                double currentImaginary = maximumImaginaryRange_ - touchY * imaginaryPixelIncrement;
                double currentReal = minimumRealRange_ + touchX * realPixelIncrement;

                // Calculate the new real and imaginary range values
                double previousRealRange = maximumRealRange_ - minimumRealRange_;
                double previousImaginaryRange = maximumImaginaryRange_ - minimumImaginaryRange_;
                double newRealRange = previousRealRange / 2.0;
                double newImaginaryRange = previousImaginaryRange / 2.0;
                minimumRealRange_ = currentReal - newRealRange / 2.0;
                maximumRealRange_ = currentReal + newRealRange / 2.0;;
                minimumImaginaryRange_ = currentImaginary - newImaginaryRange / 2.0;
                maximumImaginaryRange_ = currentImaginary + newImaginaryRange/ 2.0;

                // TODO reuse portions of the previous iteration count values.

                currentZoomLevel_++;
                updateZoomLevel();
                break;
        }
        return true;
    }
}
