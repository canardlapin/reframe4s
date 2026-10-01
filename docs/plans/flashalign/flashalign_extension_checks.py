"""Algebra checks for the Flashalign PRD completion and proposed extensions.

This is NOT an MRI registration implementation or a performance benchmark.
Dependencies: Python 3.10+, NumPy. Run: python flashalign_extension_checks.py
All randomness is seeded. Double precision is used for numerical verification.
"""
from __future__ import annotations
import json
import numpy as np


def centered_unit(x: np.ndarray) -> tuple[np.ndarray, float]:
    """Shift before centering to avoid cancellation with a large DC offset."""
    h = np.asarray(x, dtype=np.float64) - float(x[0])
    h -= h.mean()
    n = float(np.linalg.norm(h))
    if not np.isfinite(n) or n < 1e-12:
        raise ValueError("Degenerate patch")
    return h / n, n


def sufficient_statistics(f, G, u):
    """Exact hard-gated norm: never replace L with sqrt(L^2 + epsilon).

    Uses shifted intensity moments and small dense parameter statistics.
    A production implementation must also provide validity and stable-sum gates.
    """
    f = np.asarray(f, dtype=np.float64)
    G = np.asarray(G, dtype=np.float64)
    u = np.asarray(u, dtype=np.float64)
    m, d = G.shape
    if f.shape != (m,) or u.shape != (m,):
        raise ValueError("Incompatible input shapes")
    if abs(float(u.sum())) > 1e-10 or abs(float(u @ u)-1) > 1e-10:
        raise ValueError("u must be centered and unit length")
    h = f - f[0]
    sh, shh, shu = float(h.sum()), float(h @ h), float(h @ u)
    sg, sgg = G.sum(axis=0), G.T @ G
    shg, sug = G.T @ h, G.T @ u
    L2 = shh - sh * sh / m
    if L2 < 1e-20:
        raise ValueError("Degenerate patch")
    L = np.sqrt(L2)
    c = shu / L
    a = (shg - (sh/m)*sg) / L
    H = (sgg - np.outer(sg, sg)/m - np.outer(a, a)) / L2
    b = (sug - c*a) / L
    return c, H, b


def projected_matvec(G, v, L, h):
    """J'J h without J or a parameter-by-parameter matrix."""
    q = G @ h
    q = q - q.mean() - v * (v @ q)
    return G.T @ q / (L*L)


def relative(a, b):
    return float(np.linalg.norm(a-b) / max(np.linalg.norm(b), 1e-30))


def checks(seed=9341):
    rng = np.random.default_rng(seed)
    m, d = 27, 128
    reference = rng.normal(size=m)
    u, _ = centered_unit(reference)
    f = 1e8 + rng.normal(size=m)
    G = rng.normal(size=(m, d))
    v, L = centered_unit(f)
    P = np.eye(m) - np.ones((m, m))/m - np.outer(v, v)
    J = P @ G / L
    c, H, b = sufficient_statistics(f, G, u)
    h = rng.normal(size=d)
    errors = {
        'streamed_curvature_relative_error': relative(H, J.T @ J),
        'streamed_rhs_relative_error': relative(b, J.T @ u),
        'streamed_correlation_absolute_error': abs(c-float(u @ v)),
        'matrix_free_hvp_relative_error': relative(projected_matvec(G,v,L,h), J.T @ (J @ h)),
    }
    # Generic geometry JVP/VJP contract at one linearization.
    B = rng.normal(size=(m, 3, d))
    gradients = rng.normal(size=(m, 3))
    forces = rng.normal(size=(m, 3))
    jvp = np.einsum('mck,k->mc', B, h)
    vjp = np.einsum('mck,mc->k', B, forces)
    errors['geometry_adjoint_relative_error'] = abs(float(np.sum(jvp*forces)-h @ vjp))/max(abs(float(h@vjp)),1.)
    G0 = np.einsum('mc,mck->mk', gradients, B)
    step = 1e-6
    f0 = rng.normal(size=m)
    v0, L0 = centered_unit(f0)
    P0 = np.eye(m)-np.ones((m,m))/m-np.outer(v0,v0)
    numeric = (centered_unit(f0+step*(G0@h))[0]-centered_unit(f0-step*(G0@h))[0])/(2*step)
    analytic = P0 @ (G0 @ h) / L0
    errors['directional_patch_derivative_relative_error'] = relative(analytic,numeric)
    # Soft normalization: P is no longer a projector.
    fc = f0-f0.mean()
    eps2 = 2.0
    Ls = np.sqrt(fc@fc+eps2)
    vs = fc/Ls
    C = np.eye(m)-np.ones((m,m))/m
    Ps = C-np.outer(vs,vs)
    Js = Ps@G0/Ls
    P2 = C-(2-float(vs@vs))*np.outer(vs,vs)
    errors['soft_norm_corrected_curvature_relative_error'] = relative(G0.T@P2@G0/Ls**2, Js.T@Js)
    legacy_error = relative(G0.T@Ps@G0/Ls**2, Js.T@Js)
    # Scalar PE deformation T(x)=A(x+e*d(x))+t.
    Q,_ = np.linalg.qr(rng.normal(size=(3,3)))
    if np.linalg.det(Q)<0:
        Q[:,0]*=-1
    A = Q@np.diag([1.02,0.99,1.01])
    e = rng.normal(size=3); e/=np.linalg.norm(e)
    k = rng.normal(size=(10,3))/40
    co = rng.normal(size=10)*0.15
    x = rng.normal(size=3)*20
    grad_d = np.sum((co*np.cos(k@x))[:,None]*k,axis=0)
    spatial = A@(np.eye(3)+np.outer(e,grad_d))
    determinant = np.linalg.det(A)*(1+e@grad_d)
    errors['pe_determinant_identity_absolute_error'] = abs(float(np.linalg.det(spatial)-determinant))
    def transform(z):
        return A@(z+e*float(co@np.sin(k@z)))
    jacfd = np.column_stack([(transform(x+step*np.eye(3)[i])-transform(x-step*np.eye(3)[i]))/(2*step) for i in range(3)])
    errors['pe_spatial_jacobian_relative_error'] = relative(spatial,jacfd)
    # Block Schur complement gives the same update as the complete solve.
    Z=rng.normal(size=(80,30)); full=Z.T@Z+0.2*np.eye(30)
    rhs=rng.normal(size=30); na=6
    aa,ab,bb=full[:na,:na],full[:na,na:],full[na:,na:]
    schur=bb-ab.T@np.linalg.solve(aa,ab)
    dr=np.linalg.solve(schur,rhs[na:]-ab.T@np.linalg.solve(aa,rhs[:na]))
    da=np.linalg.solve(aa,rhs[:na]-ab@dr)
    errors['pose_warp_schur_relative_error']=relative(np.r_[da,dr],np.linalg.solve(full,rhs))
    # Bound a smooth vector sine field globally by sum of derivative norms.
    K=16
    directions=rng.normal(size=(K,3))
    waves=rng.normal(size=(K,3))/50
    coeff=rng.normal(size=K)
    initial_bound=float(np.sum(abs(coeff)*np.linalg.norm(directions,axis=1)*np.linalg.norm(waves,axis=1)))
    coeff*=0.35/initial_bound
    bound=float(np.sum(abs(coeff)*np.linalg.norm(directions,axis=1)*np.linalg.norm(waves,axis=1)))
    smallest=1.0
    for point in rng.uniform(-100,100,size=(100,3)):
        Du=np.einsum('k,ki,kj->ij',coeff*np.cos(waves@point),directions,waves)
        sv=np.linalg.svd(np.eye(3)+Du,compute_uv=False)
        smallest=min(smallest,float(sv[-1]))
    if smallest < 1-bound-1e-12:
        raise AssertionError('Global derivative bound violated')
    thresholds={name:1e-7 for name in errors}
    for name,error in errors.items():
        if not np.isfinite(error) or error>thresholds[name]:
            raise AssertionError(f'{name}: {error}')
    return {
        'status':'PASS: numerical identities only, not MRI accuracy or timing',
        'errors':errors,
        'illustration':{
            'incorrect_soft_norm_projector_curvature_relative_error':legacy_error,
            'global_vector_field_derivative_bound':bound,
            'guaranteed_singular_value_lower_bound':1-bound,
            'minimum_singular_value_at_100_test_points':smallest,
        }
    }

if __name__=='__main__':
    print(json.dumps(checks(),indent=2))
