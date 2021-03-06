/****************************************************************************
Copyright 2009, Colorado School of Mines and others.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
****************************************************************************/
package edu.mines.jtk.interp;

import edu.mines.jtk.dsp.*;
import edu.mines.jtk.util.Check;
import static edu.mines.jtk.util.ArrayMath.*;

/**
 * Tensor-guided blended neighbor gridding in 2D.
 * Gridding is interpolation of a set of known sample values on a 
 * uniformly sampled grid. Here the interpolation is performed by 
 * a two-step blended neighbor process described by Hale (2009).
 * <p>
 * The first step is to compute for all samples the distance to the 
 * nearest known sample and the value of that known sample. This first
 * step produces a distance map and a nearest-neighbor interpolant.
 * <p>
 * The second step is to blend (smooth) the nearest-neighbor interpolant,
 * where the extent of smoothing varies spatially and is proportional to 
 * distances in the distance map.
 * <p>
 * In tensor-guided gridding, we replace distance with time. Time is a
 * simple term for non-Euclidean distance measured in a metric-tensor
 * field. So "nearest" now means nearest in time. In the first step we 
 * compute a time map by solving an eikonal equation with coefficients 
 * that may be both anisotropic and spatially varying. In the second 
 * step, we blend the nearest-neighbor interpolant with an anisotropic 
 * and spatially varying smoothing filter.
 * <p>
 * The default tensor field is homogeneous and isotropic. In this
 * special case, time is equivalent to distance, and tensor-guided
 * gridding is similar to gridding with Sibson's natural neighbor 
 * interpolant.
 * <p>
 * Reference: 
 * <a href="http://www.mines.edu/~dhale/papers/Hale09ImageGuidedBlendedNeighborInterpolation.pdf">
 * Hale, D., 2009, Image-guided blended neighbor interpolation, CWP-634</a>
 * @author Dave Hale, Colorado School of Mines
 * @version 2009.07.21
 */
public class BlendedGridder2 implements Gridder2 {

  /**
   * Constructs a gridder for default tensors.
   */
  public BlendedGridder2() {
    this(null);
  }

  /**
   * Constructs a gridder for default tensors and specified samples.
   * The specified arrays are referenced; not copied.
   * @param f array of sample values f(x1,x2).
   * @param x1 array of sample x1 coordinates.
   * @param x2 array of sample x2 coordinates.
   */
  public BlendedGridder2(float[] f, float[] x1, float[] x2) {
    this(null);
    setScattered(f,x1,x2);
  }

  /**
   * Constructs a gridder for the specified tensors.
   * @param tensors the tensors.
   */
  public BlendedGridder2(Tensors2 tensors) {
    setTensors(tensors);
  }

  /**
   * Constructs a gridder for the specified tensors and samples.
   * The specified arrays are referenced; not copied.
   * @param tensors the tensors.
   * @param f array of sample values f(x1,x2).
   * @param x1 array of sample x1 coordinates.
   * @param x2 array of sample x2 coordinates.
   */
  public BlendedGridder2(
    Tensors2 tensors,
    float[] f, float[] x1, float[] x2) 
  {
    setTensors(tensors);
    setScattered(f,x1,x2);
  }

  /**
   * Sets the tensor field used by this gridder.
   * The default is a homogeneous and isotropic tensor field.
   * @param tensors the tensors; null for default tensors.
   */
  public void setTensors(Tensors2 tensors) {
    _tensors = tensors;
    if (_tensors==null) {
      _tensors = new Tensors2() {
        public void getTensor(int i1, int i2, float[] d) {
          d[0] = 1.0f;
          d[1] = 0.0f;
          d[2] = 1.0f;
        }
      };
    }
  }

  /**
   * Enables or disables blending in {@link #grid(Sampling,Sampling)}.
   * If true (the default), that method will perform both of the two
   * steps described; that is, it will blend (smooth) after computing
   * the nearest neighbor interpolant. If false, that method will perform 
   * only the first step and return the nearest neighbor interpolant. The
   * default is that blending is enabled.
   * @param blending true, for blending; false, otherwise.
   */
  public void setBlending(boolean blending) {
    _blending = blending;
  }

  /**
   * Sets the local diffusion kernel used to perform blending.
   * The default kernel uses a 2x2 stencil to compute derivatives.
   * @param ldk the local diffusion kernel.
   */
  public void setBlendingKernel(LocalDiffusionKernel ldk) {
    _ldk = ldk;
  }

  /**
   * Sets the smoothness of the interpolation of gridded values.
   * The default is 0.5, which yields an interpolant with linear precision. 
   * Larger values yield smoother interpolants with plateaus at known samples.
   * @param smoothness the smoothness.
   */
  public void setSmoothness(double smoothness) {
    _c = 0.25f/(float)smoothness;
  }

  /**
   * Sets the maximum time computed by this gridder. The gridder has 
   * linear precision where times are less than the maximum time.
   * @param tmax the maximum time.
   */
  public void setTimeMax(double tmax) {
    _tmax = (float)tmax;
  }

  /**
   * Experimental use only.
   */
  public void setTimeMarkerX(boolean tmx) {
    _tmx = tmx;
  }
  /**
   * Experimental use only.
   */
  public double getTimeMarkerS() {
    return _tms;
  }

  /**
   * Computes gridded values using nearest neighbors.
   * Gridded values in the array p are computed for only unknown 
   * samples with value equal to the specified null value. Any
   * known (non-null) sample values in the array p are not changed.
   * <p>
   * This method also computes and returns an array of times to
   * nearest-neighbor samples. Times are zero for known samples 
   * and are positive for gridded samples.
   * @param pnull the null value representing unknown samples.
   * @param p array of sample values to be gridded.
   * @return array of times to nearest known samples.
   */
  public float[][] gridNearest(float pnull, float[][] p) {
    int n1 = p[0].length;
    int n2 = p.length;

    // Make array of times, zero for samples with non-null values.
    // Also, count the number of marks that we will need.
    int nmark = 0;
    float[][] t = new float[n2][n1];
    float tnull = -FLT_MAX;
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        if (p[i2][i1]==pnull) {
          t[i2][i1] = tnull;
        } else {
          t[i2][i1] = 0.0f;
          ++nmark;
        }
      }
    }
    gridNearest(nmark,t,p);
    return t;
  }

  /**
   * Computes gridded values using nearest neighbors.
   * Gridded values in the array p are computed for only unknown 
   * samples denoted by corresponding non-zero times in the array t. 
   * This method does not change known values in p, which correspond
   * to zero times in t.
   * @param t array of times to nearest known samples.
   * @param p array of nearest-neighbor gridded values.
   */
  public void gridNearest(float[][] t, float[][] p) {
    int n1 = t[0].length;
    int n2 = t.length;

    // Count the known samples, the number of marks we need.
    int nmark = 0;
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        if (t[i2][i1]==0.0f)
          ++nmark;
      }
    }
    gridNearest(nmark,t,p);
  }

  /**
   * Computes gridded values using blended neighbors. 
   * Note that blended-neighbor gridding can be performed only 
   * after nearest-neighbor gridding. Blending does not change
   * the values of known samples for which times are zero.
   * @param t array of times to nearest known samples.
   * @param p array of nearest-neighbor gridded values.
   * @param q array of blended-neighbor gridded values.
   */
  public void gridBlended(float[][] t, float[][] p, float[][] q) {
    int n1 = t[0].length;
    int n2 = t.length;

    // Compute time squared. If necessary, shift to account for the shift 
    // in the finite-difference stencil used in the local diffusion kernel.
    float[][] s = mul(t,t);
    if (_ldk.getStencil()!=LocalDiffusionKernel.Stencil.D21) {
      for (int i2=n2-1; i2>0; --i2) {
        for (int i1=n1-1; i1>0; --i1) {
          s[i2][i1] = 0.25f*(s[i2  ][i1  ] +
                             s[i2  ][i1-1] +
                             s[i2-1][i1  ] +
                             s[i2-1][i1-1]);
        }
      }
    }

    // Construct and apply a local smoothing filter.
    LocalSmoothingFilter lsf = new LocalSmoothingFilter(0.01,10000,_ldk);
    lsf.setPreconditioner(true);
    float pavg = sum(p)/n1/n2;
    float[][] r = sub(p,pavg);
    // Smoothing attenuates finite-difference errors near Nyquist.
    // The problem with this smoothing is that it makes q != p when
    // known samples are adjacent, as when interpolating well logs.
    // This smoothing should be unnecessary for Stencil.D21.
    if (_ldk.getStencil()!=LocalDiffusionKernel.Stencil.D21)
      lsf.applySmoothS(r,r);
    lsf.apply(_tensors,_c,s,r,q);
    add(q,pavg,q);

    // Restore the known sample values. Due to errors in finite-difference
    // approximations, these values may have changed during smoothing.
    // Even with time adjustments, this restoration is still necessary
    // if we used applySmoothS above. Best to just do this in any case.
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        if (t[i2][i1]==0.0f) {
          q[i2][i1] = p[i2][i1];
        }
      }
    }
  }


  ///////////////////////////////////////////////////////////////////////////
  // interface Gridder2

  public void setScattered(float[] f, float[] x1, float[] x2) {
    _f = f;
    _x1 = x1;
    _x2 = x2;
  }

  public float[][] grid(Sampling s1, Sampling s2) {
    Check.argument(s1.isUniform(),"s1 is uniform");
    Check.argument(s2.isUniform(),"s2 is uniform");
    Check.state(_f!=null,"scattered samples have been set");
    Check.state(_x1!=null,"scattered samples have been set"); 
    Check.state(_x2!=null,"scattered samples have been set");
    int n1 = s1.getCount();
    int n2 = s2.getCount();
    SimpleGridder2 sg = new SimpleGridder2(_f,_x1,_x2);
    float pnull = -FLT_MAX;
    float tnull = -FLT_MAX;
    sg.setNullValue(pnull);
    float[][] p = sg.grid(s1,s2);
    float[][] t = new float[n2][n1];
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        t[i2][i1] = (p[i2][i1]!=pnull)?0.0f:tnull;
      }
    }
    gridNearest(t,p);
    float[][] q = p;
    if (_blending) {
      q = new float[n2][n1];
      gridBlended(t,p,q);
    }
    return q;
  }

  ///////////////////////////////////////////////////////////////////////////
  // private

  private boolean _tmx; // true if using experimental time marker
  private double _tms; // time marker CPU time in seconds

  private Tensors2 _tensors;
  private float[] _f,_x1,_x2;
  private boolean _blending = true;
  private float _tmax = FLT_MAX;
  private float _c = 0.5f;
  private LocalDiffusionKernel _ldk =
    new LocalDiffusionKernel(LocalDiffusionKernel.Stencil.D22);

  private void gridNearest(int nmark, float[][] t, float[][] p) {
    int n1 = t[0].length;
    int n2 = t.length;

    // Make an array for marks, while storing values of known samples
    // in an array of values indexed by the mark.
    float[] pmark = new float[nmark];
    int[][] m = new int[n2][n1];
    int mark = 0;
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        if (t[i2][i1]==0.0f) {
          pmark[mark] = p[i2][i1];
          m[i2][i1] = mark;
          ++mark;
        }
      }
    }

    // Use the time marker to compute both times and marks.
    edu.mines.jtk.util.Stopwatch sw = new edu.mines.jtk.util.Stopwatch();
    if (_tmx) {
      TimeMarker2X tm = new TimeMarker2X(n1,n2,_tensors);
      //tm.setConcurrency(TimeMarker2X.Concurrency.SERIAL);
      sw.start();
      tm.apply(t,m);
      sw.stop();
    } else {
      TimeMarker2 tm = new TimeMarker2(n1,n2,_tensors);
      //tm.setConcurrency(TimeMarker2.Concurrency.SERIAL);
      sw.start();
      tm.apply(t,m);
      sw.stop();
    }
    _tms = sw.time();

    // Adjust times to ensure interpolation of known samples.
    adjustTimes(nmark,m,t);

    // Use the marks to compute the nearest-neighbor interpolant.
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        float ti = t[i2][i1];
        if (ti!=0.0f)
          p[i2][i1] = pmark[m[i2][i1]];
        if (ti>_tmax)
          t[i2][i1] = _tmax;
      }
    }
  }

  // Adjusts times to be nearly zero in neighborhood of known samples.
  private void adjustTimes(int nmark, int[][] m, float[][] t) {
    int n1 = m[0].length;
    int n2 = m.length;
    int n1m = n1-1;
    int n2m = n2-1;
    float[] s = new float[nmark];
    for (int i2=0; i2<n2; ++i2) {
      int i2m = (i2>0  )?i2-1:i2;
      int i2p = (i2<n2m)?i2+1:i2;
      for (int i1=0; i1<n1; ++i1) {
        int i1m = (i1>0  )?i1-1:i1;
        int i1p = (i1<n1m)?i1+1:i1;
        if (t[i2][i1]==0.0f) {
          float tmax = 0.0f;
          tmax = max(tmax,t[i2m][i1m]);
          tmax = max(tmax,t[i2m][i1 ]);
          tmax = max(tmax,t[i2m][i1p]);
          tmax = max(tmax,t[i2 ][i1m]);
          tmax = max(tmax,t[i2 ][i1p]);
          tmax = max(tmax,t[i2p][i1m]);
          tmax = max(tmax,t[i2p][i1 ]);
          tmax = max(tmax,t[i2p][i1p]);
          s[m[i2][i1]] = tmax;
        }
      }
    }
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        if (t[i2][i1]>0.0f)
          t[i2][i1] = max(FLT_MIN,t[i2][i1]-s[m[i2][i1]]);
      }
    }
  }
}
