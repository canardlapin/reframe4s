#!/usr/bin/env python3
"""Prepare acquired images and independently inspect persisted Scala pull maps.

Requires numpy, scipy, nibabel and matplotlib; ANTs comparison uses antspyx.
No evaluation labels enter prepare() or the Scala registration runner.
"""
import argparse
import hashlib
import json
import importlib.util
import time
from pathlib import Path

import nibabel as nib
import numpy as np
from scipy import ndimage
from nibabel.processing import resample_from_to


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def save_image(path, values, affine, dtype=np.float64):
    image = nib.Nifti1Image(np.asarray(values, dtype=dtype), affine)
    image.set_sform(affine, code=1)
    image.set_qform(affine, code=1)
    nib.save(image, path)


def fetch(args):
    """Retrieve only the pinned public processed OASIS subset, never raw MRI."""
    script = Path(__file__).resolve().parents[2] / "tools/datasets.py"
    spec = importlib.util.spec_from_file_location("reframe4s_datasets", script)
    datasets = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(datasets)
    datasets.fetch_oasis_legacy(args.output, args.archive)


def prepare(args):
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=False)
    spacing = args.spacing_mm
    assert np.isfinite(spacing) and spacing > 0
    inputs = {}
    for name in ("moving", "fixed"):
        path, mask_path = Path(getattr(args, name)), Path(getattr(args, name + "_mask"))
        image, mask = nib.load(path), nib.load(mask_path)
        assert image.shape == mask.shape and np.allclose(image.affine, mask.affine, atol=1e-6)
        values, mask_values = image.get_fdata(), mask.get_fdata() > 0
        assert np.isfinite(values).all() and mask_values.any()
        indices = np.argwhere(mask_values)
        corners = np.array(np.meshgrid(*zip(indices.min(0), indices.max(0)), indexing="ij")).reshape(3, -1).T
        world = nib.affines.apply_affine(image.affine, corners)
        lower = np.floor((world.min(0) - 24) / spacing) * spacing
        upper = np.ceil((world.max(0) + 24) / spacing) * spacing
        shape = tuple(np.rint((upper - lower) / spacing).astype(int) + 1)
        affine = np.diag([spacing, spacing, spacing, 1.])
        affine[:3, 3] = lower
        brain = nib.Nifti1Image(values * mask_values, image.affine)
        prepared = resample_from_to(brain, (shape, affine), order=1).get_fdata()
        prepared_mask = resample_from_to(mask, (shape, affine), order=0).get_fdata() > 0
        scale = float(np.percentile(prepared[prepared > 0], 99))
        prepared /= scale
        save_image(out / f"{name}.nii.gz", prepared, affine)
        save_image(out / f"{name}-mask.nii.gz", prepared_mask, affine)
        inputs[name] = {"path": str(path.resolve()), "sha256": sha(path),
                        "mask_path": str(mask_path.resolve()), "mask_sha256": sha(mask_path),
                        "shape": list(map(int, shape)), "affine": affine.tolist(), "intensity_divisor": scale}
    write_json(out / "preparation.json", {"inputs": inputs, "spacing_mm": spacing, "padding_mm": 24,
        "evaluation_labels_used": False, "outputs": {p.name: sha(p) for p in out.glob("*.nii.gz")}})


def read_pull(directory, name):
    meta = json.loads((directory / (name + ".json")).read_text())
    shape = tuple(meta["shape"])
    coords = np.fromfile(directory / (name + ".f64"), dtype=">f8").reshape(-1, 3)
    coords = np.stack([coords[:, c].reshape(shape, order="F") for c in range(3)], axis=-1)
    valid = np.fromfile(directory / (name + ".valid-u8"), dtype=np.uint8).reshape(shape, order="F") > 0
    warped = np.fromfile(directory / (name + "-warped.f64"), dtype=">f8").reshape(shape, order="F")
    return coords, valid, warped, np.array(meta["index_to_ras_mm"]).reshape(4, 4)


def sample(values, source_affine, world, order=1):
    vox = nib.affines.apply_affine(np.linalg.inv(source_affine), world)
    good = np.isfinite(vox).all(-1) & (vox >= 0).all(-1) & (vox <= np.array(values.shape[:3]) - 1).all(-1)
    coords = np.moveaxis(vox, -1, 0)
    if values.ndim == 3:
        result = ndimage.map_coordinates(values, coords, order=order, mode="constant", cval=0, prefilter=False)
    else:
        result = np.stack([ndimage.map_coordinates(values[..., c], coords, order=order,
            mode="constant", cval=0, prefilter=False) for c in range(values.shape[-1])], axis=-1)
    return result, good


def ncc(a, b, mask):
    return float(np.corrcoef(a[mask], b[mask])[0, 1])


def dice(a, b):
    den = int(a.sum() + b.sum())
    return 1. if den == 0 else float(2 * (a & b).sum() / den)


def anatomy(fixed_labels, moving_labels, coords, target_affine):
    """Evaluation only. Never return labels or label-derived weights to fitting."""
    target = resample_from_to(fixed_labels, (coords.shape[:3], target_affine), order=0).get_fdata().astype(int)
    source = moving_labels.get_fdata()
    warped, _ = sample(source, moving_labels.affine, coords, order=0)
    labels = sorted(set(np.unique(fixed_labels.get_fdata()).astype(int)) & set(np.unique(source).astype(int)) - {0})
    return label_scores(target, warped, labels, target_affine)


def label_scores(target, warped, labels, target_affine):
    rows = []
    spacing = nib.affines.voxel_sizes(target_affine)
    axes = target_affine[:3, :3] / spacing
    assert np.allclose(axes.T @ axes, np.eye(3), atol=1e-8), "HD95 requires orthogonal prepared axes"
    assert len(labels) > 0, "no shared anatomical labels"
    for label in labels:
        a, b = target == label, warped == label
        hd = None
        if a.any() and b.any():
            edge_a = a & ~ndimage.binary_erosion(a)
            edge_b = b & ~ndimage.binary_erosion(b)
            distances = np.concatenate([ndimage.distance_transform_edt(~edge_b, sampling=spacing)[edge_a],
                                        ndimage.distance_transform_edt(~edge_a, sampling=spacing)[edge_b]])
            hd = float(np.percentile(distances, 95))
        rows.append({"label": int(label), "dice": dice(a, b), "hd95_mm": hd,
                     "fixed_voxels": int(a.sum()), "warped_voxels": int(b.sum())})
    missing = sum(r["hd95_mm"] is None for r in rows)
    return {"labels": rows, "label_count": len(rows), "missing_labels": missing,
            "macro_dice": float(np.mean([r["dice"] for r in rows])),
            "median_hd95_mm": None if missing else float(np.median([r["hd95_mm"] for r in rows]))}


def evaluate(args):
    data, directory = Path(args.data), Path(args.output)
    images = {n: nib.load(data / f"{n}.nii.gz") for n in ("moving", "fixed")}
    mask_images = {n: nib.load(data / f"{n}-mask.nii.gz") for n in images}
    for n, mask in mask_images.items():
        assert mask.shape == images[n].shape and np.allclose(mask.affine, images[n].affine, rtol=0, atol=1e-8), "mask grid mismatch"
    masks = {n: mask.get_fdata() > 0 for n, mask in mask_images.items()}
    maps = {}
    for direction in ("fixed-to-moving", "moving-to-fixed"):
        name = args.lane + "-" + direction
        maps[direction] = read_pull(directory, name)
    result = {"lane": args.lane, "directions": {}, "anatomical_labels": "not supplied",
              "input_sha256": {n: sha(data / f"{n}.nii.gz") for n in ("fixed", "moving", "fixed-mask", "moving-mask")}}
    if args.lane.startswith("halfflow"):
        result["native_export_accepted"] = (directory / "export-diagnostics.txt").read_text().splitlines()[0] == "accepted"
        result["recipe"] = {"mode": (directory / "mode.txt").read_text().strip(), "plan_sha256": sha(directory / "plan.txt")}
    labels = None
    if args.fixed_labels or args.moving_labels:
        assert args.fixed_labels and args.moving_labels, "both label volumes are required"
        labels = {"fixed": nib.load(args.fixed_labels), "moving": nib.load(args.moving_labels)}
        result["anatomical_labels"] = {n: {"path": str(getattr(args, n + "_labels")),
            "sha256": sha(getattr(args, n + "_labels"))} for n in labels}
    for direction, (coords, valid, warped, affine) in maps.items():
        target, source = direction.split("-to-")
        assert coords.shape[:3] == images[target].shape and np.allclose(affine, images[target].affine, rtol=0, atol=1e-8), "map target grid mismatch"
        original = images[source].get_fdata()
        reloaded, inside = sample(original, images[source].affine, coords)
        support = valid & inside
        difference = np.abs(reloaded - warped)
        save_image(directory / f"{args.lane}-{direction}-warped.nii.gz", warped, affine)
        # Independent physical-coordinate finite differences: d(source)/d(index) * d(index)/d(world).
        jac_index = np.stack([np.stack(np.gradient(coords[..., c], edge_order=1), axis=-1) for c in range(3)], axis=-2)
        jac = jac_index @ np.linalg.inv(affine[:3, :3])
        determinant = np.linalg.det(jac)
        interior = ndimage.binary_erosion(np.ones(valid.shape, dtype=bool), structure=np.ones((3, 3, 3)), border_value=0)
        complete_valid = ndimage.binary_erosion(valid, structure=np.ones((3, 3, 3)), border_value=0)
        brain = masks[target]
        reverse = maps[source + "-to-" + target]
        back, reverse_inside = sample(reverse[0], reverse[3], coords)
        reverse_valid, _ = sample(reverse[1].astype(float), reverse[3], coords)
        roundtrip_valid = valid & reverse_inside & (reverse_valid >= 1 - 1e-10)
        world = nib.affines.apply_affine(affine, np.moveaxis(np.indices(brain.shape), 0, -1))
        residual = np.linalg.norm(back - world, axis=-1)
        region = brain & roundtrip_valid
        assert support.any() and region.any() and (brain & interior).any()
        warped_mask, _ = sample(masks[source].astype(float), images[source].affine, coords, order=0)
        row = {"target": target, "source": source,
               "map_sha256": sha(directory / (args.lane + "-" + direction + ".f64")),
               "all_map_values_finite": bool(np.isfinite(coords).all() and np.isfinite(warped).all()),
               "grid_jacobian_invalid": int((interior & ~complete_valid).sum()),
               "grid_jacobian_nonfinite": int((interior & ~np.isfinite(determinant)).sum()),
               "reload_max_abs": float(difference[support].max()),
               "reload_evaluated": int(support.sum()), "reload_excluded": int((~support).sum()),
               "ncc": ncc(images[target].get_fdata(), warped, brain),
               "brain_mask_dice": dice(brain, warped_mask > 0.5),
               "brain_sampling_coverage": float(support[brain].mean()),
               "grid_jacobian_min": float(determinant[interior].min()),
               "grid_jacobian_nonpositive": int((determinant[interior] <= 0).sum()),
               "grid_jacobian_evaluated": int(interior.sum()),
               "brain_jacobian_min": float(determinant[brain & interior].min()),
               "brain_roundtrip_coverage": float(roundtrip_valid[brain].mean()),
               "brain_roundtrip_max_mm": float(residual[region].max()),
               "brain_roundtrip_p95_mm": float(np.percentile(residual[region], 95)),
               "whole_valid_roundtrip_max_mm": float(residual[roundtrip_valid].max())}
        row["geometry_pass"] = bool(row["all_map_values_finite"] and row["grid_jacobian_invalid"] == 0
            and row["grid_jacobian_nonfinite"] == 0 and row["grid_jacobian_nonpositive"] == 0 and row["brain_jacobian_min"] >= .05
            and row["brain_sampling_coverage"] >= .99 and row["brain_roundtrip_coverage"] >= .99
            and row["brain_roundtrip_max_mm"] <= .2 and row["reload_max_abs"] <= 1e-6 * max(1., np.abs(original).max()))
        if labels:
            row["anatomy"] = anatomy(labels[target], labels[source], coords, affine)
        result["directions"][direction] = row
    result["geometry_pass"] = all(r["geometry_pass"] for r in result["directions"].values())
    write_json(directory / (args.lane + "-evaluation.json"), result)
    print(json.dumps({"lane": result["lane"], "geometry_pass": result["geometry_pass"],
        "directions": {k: {a: b for a, b in v.items() if a != "anatomy"} |
            ({"anatomy": {a: b for a, b in v["anatomy"].items() if a != "labels"}} if "anatomy" in v else {})
            for k, v in result["directions"].items()}}, indent=2))
    if not result["geometry_pass"]:
        raise SystemExit(2)


def assess_reports(affine, nonlinear, frozen_recipe):
    """Require the declared two-direction anatomical improvement, with matched inputs."""
    assert affine["input_sha256"] == nonlinear["input_sha256"], "different prepared inputs"
    assert affine["lane"] == "affine" and nonlinear["lane"] == "halfflow", "accepted lanes required"
    assert nonlinear["recipe"] == frozen_recipe, "recipe differs from frozen development recipe"
    identities = lambda report: {k: v["sha256"] for k, v in report["anatomical_labels"].items()}
    assert identities(affine) == identities(nonlinear), "different evaluation labels"
    rows = {}
    for direction in ("fixed-to-moving", "moving-to-fixed"):
        baseline, candidate = [r["directions"][direction]["anatomy"] for r in (affine, nonlinear)]
        assert [r["label"] for r in baseline["labels"]] == [r["label"] for r in candidate["labels"]], "different label sets"
        complete = candidate["label_count"] > 0 and baseline["missing_labels"] == candidate["missing_labels"] == 0
        dice_gain = candidate["macro_dice"] - baseline["macro_dice"]
        hd_reduction = baseline["median_hd95_mm"] - candidate["median_hd95_mm"] if complete else None
        rows[direction] = {"dice_gain": dice_gain, "median_hd95_reduction_mm": hd_reduction,
                           "anatomy_pass": bool(complete and dice_gain > 0 and hd_reduction > 0)}
    return {"pass": bool(affine["geometry_pass"] and nonlinear["geometry_pass"] and nonlinear["native_export_accepted"]
                         and all(row["anatomy_pass"] for row in rows.values())), "directions": rows}


def assess(args):
    paths = [Path(args.affine_report), Path(args.halfflow_report)]
    recipe = Path(args.frozen_recipe)
    result = assess_reports(*(json.loads(p.read_text()) for p in paths), json.loads(recipe.read_text()))
    result["frozen_recipe_sha256"] = sha(recipe)
    result["report_sha256"] = {str(p): sha(p) for p in paths}
    write_json(Path(args.output), result)
    print(json.dumps(result, indent=2))
    if not result["pass"]:
        raise SystemExit(2)


def comparator(args):
    import ants
    data, out = Path(args.data), Path(args.output)
    out.mkdir(parents=True, exist_ok=False)
    fixed, moving = [ants.image_read(str(data / (n + ".nii.gz"))) for n in ("fixed", "moving")]
    mask = ants.image_read(str(data / "fixed-mask.nii.gz"))
    settings = {"type_of_transform": "SyN", "mask_all_stages": True,
        "aff_metric": "mattes", "aff_sampling": 32, "aff_random_sampling_rate": 1.0,
        "aff_iterations": (1000, 500, 250, 0), "aff_shrink_factors": (6, 4, 2, 1),
        "aff_smoothing_sigmas": (3, 2, 1, 0), "syn_metric": "CC", "syn_sampling": 2,
        "reg_iterations": (40, 20, 0), "random_seed": 20261002}
    write_json(out / "settings.json", {"antspyx_version": ants.__version__, "settings": settings,
        "fixed_sha256": sha(data / "fixed.nii.gz"), "moving_sha256": sha(data / "moving.nii.gz")})
    started = time.monotonic()
    result = ants.registration(fixed, moving, mask=mask, outprefix=str(out / "ants-"), **settings)
    ants.image_write(result["warpedmovout"], str(out / "warped.nii.gz"))
    ants.image_write(result["warpedfixout"], str(out / "inverse-warped.nii.gz"))
    receipt = {"seconds": time.monotonic() - started, "forward": result["fwdtransforms"],
               "inverse": result["invtransforms"]}
    write_json(out / "result.json", receipt)
    print(json.dumps(receipt, indent=2))


def evaluate_ants(args):
    import ants
    data, out = Path(args.data), Path(args.output)
    receipt = json.loads((out / "result.json").read_text())
    fixed = ants.image_read(str(data / "fixed.nii.gz"))
    moving = ants.image_read(str(data / "moving.nii.gz"))
    labels = {n: nib.load(getattr(args, n + "_labels")) for n in ("fixed", "moving")}
    common = sorted(set(np.unique(labels["fixed"].get_fdata()).astype(int)) &
                    set(np.unique(labels["moving"].get_fdata()).astype(int)) - {0})
    rows = {}
    for target, source, reference, transforms, invert, image_name in [
        ("fixed", "moving", fixed, receipt["forward"], [False, False], "warped.nii.gz"),
        ("moving", "fixed", moving, receipt["inverse"], [True, False], "inverse-warped.nii.gz")]:
        warped_labels = ants.apply_transforms(reference, ants.image_read(getattr(args, source + "_labels")),
            transformlist=transforms, whichtoinvert=invert, interpolator="nearestNeighbor")
        label_path = out / f"{source}-labels-warped.nii.gz"
        ants.image_write(warped_labels, str(label_path))
        reference_nib = nib.load(data / (target + ".nii.gz"))
        target_labels = resample_from_to(labels[target], reference_nib, order=0).get_fdata().astype(int)
        scores = label_scores(target_labels, nib.load(label_path).get_fdata(), common, reference_nib.affine)
        mask = nib.load(data / (target + "-mask.nii.gz")).get_fdata() > 0
        scores["ncc"] = ncc(reference_nib.get_fdata(), nib.load(out / image_name).get_fdata(), mask)
        scores["target"], scores["source"] = target, source
        rows[source + "-to-" + target] = scores
    write_json(out / "anatomy-evaluation.json", rows)
    print(json.dumps({k: {a: b for a, b in v.items() if a != "labels"} for k, v in rows.items()}, indent=2))


def plot(args):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fixed = nib.load(args.fixed)
    warped = resample_from_to(nib.load(args.warped), fixed, order=1).get_fdata()
    values = fixed.get_fdata()
    mask = nib.load(args.mask).get_fdata() > 0
    center = np.rint(np.argwhere(mask).mean(0)).astype(int)
    fig, axes = plt.subplots(3, 4, figsize=(12, 10), constrained_layout=True)
    for axis in range(3):
        f = np.take(values, center[axis], axis=axis).T
        w = np.take(warped, center[axis], axis=axis).T
        overlay = np.stack([np.clip(f, 0, 1), np.clip(w, 0, 1), np.zeros_like(f)], axis=-1)
        panels = [f, w, overlay, np.abs(f - w)]
        for col, panel in enumerate(panels):
            axes[axis, col].imshow(panel, origin="lower", cmap="magma" if col == 3 else "gray",
                                   vmin=0, vmax=.4 if col == 3 else 1)
            axes[axis, col].axis("off")
            if axis == 0:
                axes[axis, col].set_title(["Fixed", "Registered", "Fixed red / registered green", "Absolute difference"][col])
        axes[axis, 0].set_ylabel(["Sagittal", "Coronal", "Axial"][axis])
    fig.suptitle(args.title)
    fig.savefig(args.output, dpi=140)
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("fetch")
    p.add_argument("--output", required=True)
    p.add_argument("--archive", help="reuse an already downloaded archive after checking its pinned SHA-256")
    p.set_defaults(func=fetch)
    p = commands.add_parser("prepare")
    for name in ("moving", "fixed", "moving-mask", "fixed-mask", "output"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--spacing-mm", type=float, default=3.0)
    p.set_defaults(func=prepare)
    p = commands.add_parser("evaluate")
    for name in ("data", "output", "lane"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--fixed-labels")
    p.add_argument("--moving-labels")
    p.set_defaults(func=evaluate)
    p = commands.add_parser("assess")
    for name in ("affine-report", "halfflow-report", "frozen-recipe", "output"):
        p.add_argument("--" + name, required=True)
    p.set_defaults(func=assess)
    p = commands.add_parser("ants")
    p.add_argument("--data", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(func=comparator)
    p = commands.add_parser("evaluate-ants")
    for name in ("data", "output", "fixed-labels", "moving-labels"):
        p.add_argument("--" + name, required=True)
    p.set_defaults(func=evaluate_ants)
    p = commands.add_parser("plot")
    for name in ("fixed", "warped", "mask", "output", "title"):
        p.add_argument("--" + name, required=True)
    p.set_defaults(func=plot)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
