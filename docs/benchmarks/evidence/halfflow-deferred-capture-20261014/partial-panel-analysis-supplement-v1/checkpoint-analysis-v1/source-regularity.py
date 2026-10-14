"""Physical first-derivative norms of saved midpoint residual displacement.

These are descriptive geometric diagnostics, not optimizer penalties. Report
the exact valid interior-stencil denominator at every pyramid resolution.
"""
import numpy as np
from scipy.ndimage import binary_erosion, generate_binary_structure


def displacement_regularity(coords, valid, affine):
    coords=np.asarray(coords,dtype=float);valid=np.asarray(valid,dtype=bool)
    affine=np.asarray(affine,dtype=float)
    if coords.shape != valid.shape+(3,) or min(valid.shape)<3:
        raise ValueError('A three-dimensional coordinate grid with an interior is required')
    if not np.isfinite(coords).all() or not np.isfinite(affine).all():
        raise ValueError('Finite coordinates and lattice required')
    inverse=np.linalg.inv(affine[:3,:3])
    indices=np.moveaxis(np.indices(valid.shape,dtype=float),0,-1)
    world=indices@affine[:3,:3].T+affine[:3,3]
    displacement=coords-world
    stencil=binary_erosion(valid,structure=generate_binary_structure(3,1),border_value=0)
    if not stencil.any():raise ValueError('No valid central-difference stencil')
    squared_gradient=np.zeros(valid.shape)
    for component in range(3):
        derivatives=np.stack(np.gradient(displacement[...,component]),axis=-1)
        physical=derivatives@inverse
        squared_gradient+=np.sum(physical*physical,axis=-1)
    squared_displacement=np.sum(displacement*displacement,axis=-1)
    return {'grid_voxels':int(valid.size),'valid_center_voxels':int(valid.sum()),
            'valid_interior_stencil_voxels':int(stencil.sum()),
            'stencil_coverage':float(stencil.mean()),
            'rms_displacement_mm':float(np.sqrt(squared_displacement[stencil].mean())),
            'maximum_displacement_mm':float(np.sqrt(squared_displacement[stencil].max())),
            'rms_displacement_gradient_frobenius':float(np.sqrt(squared_gradient[stencil].mean())),
            'maximum_displacement_gradient_frobenius':float(np.sqrt(squared_gradient[stencil].max())),
            'scope':'All valid central-difference stencils on the original midpoint grid; no mask or affine arm; counts explicit',
            'continuous_invertibility_claim':False}
