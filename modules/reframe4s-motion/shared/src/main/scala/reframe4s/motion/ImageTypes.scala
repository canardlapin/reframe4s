package reframe4s.motion

import image4s.ContinuousImage
import image4s.SampleSpace
import ravel.AnyRank
import image4s.geometry.Dim
import image4s.geometry.Frame

/** Continuous Double-valued intensity data accepted by motion algorithms.
  *
  * This is an algorithm-specific input constraint, not a second image
  * representation. The complete image value remains image4s `Sampled`, and
  * its sample-space owner remains existentially precise.
  */
type MotionScalarImage[
    F <: Frame[D],
    D <: Dim,
    R <: AnyRank
] = ContinuousImage[? <: SampleSpace[F, D], Double, R]
