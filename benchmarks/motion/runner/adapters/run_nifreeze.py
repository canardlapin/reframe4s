#!/usr/bin/env python3
"""Pinned nifreeze a3985fe5 benchmark adapter.

The raw matrices written here are the fixed-to-moving physical RAS pull
transforms consumed by nitransforms.resampling.apply. The Scala runner inverts
them exactly once to produce canonical moving-to-fixed pose records.
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path

import nibabel as nb
import numpy as np

PINNED_REVISION = "a3985fe5a4763f0a2eafb24e78d88fcfad60992a"


def probe() -> tuple[bool, str]:
    if shutil.which("antsRegistration") is None:
        return False, "missing ANTs executable: antsRegistration"
    try:
        import nifreeze
        import nipype
        import nitransforms

        from nifreeze.registration.ants import (
            _prepare_registration_data,
            _run_registration,
        )

        del _prepare_registration_data, _run_registration
        return (
            True,
            " ".join(
                (
                    f"nifreeze={getattr(nifreeze, '__version__', 'unknown')}",
                    f"required_revision={PINNED_REVISION}",
                    f"nipype={getattr(nipype, '__version__', 'unknown')}",
                    f"nitransforms={getattr(nitransforms, '__version__', 'unknown')}",
                )
            ),
        )
    except Exception as error:
        return False, f"failed to import pinned nifreeze stack: {error!r}"


def prediction(
    data: np.ndarray,
    frame: int,
    reference_index: int,
    court: str,
) -> np.ndarray:
    if court == "common_resampler_estimation":
        return data[..., reference_index]
    keep = np.ones(data.shape[-1], dtype=bool)
    keep[frame] = False
    return np.median(data[..., keep], axis=-1)


def write_raw_matrices(path: Path, matrices: np.ndarray) -> None:
    with path.open("x", encoding="utf-8") as stream:
        for matrix in matrices:
            stream.write(" ".join(f"{value:.17g}" for value in matrix.reshape(-1)))
            stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def write_stage_times(
    path: Path,
    decode: float,
    prepare: float,
    estimate: float,
    apply: float | None,
    encode: float | None,
    report: float,
) -> None:
    header = (
        "decode_seconds\tprepare_seconds\testimate_seconds\t"
        "apply_seconds\tencode_seconds\treport_seconds\n"
    )
    values = "\t".join(
        (
            f"{decode:.17g}",
            f"{prepare:.17g}",
            f"{estimate:.17g}",
            "NA" if apply is None else f"{apply:.17g}",
            "NA" if encode is None else f"{encode:.17g}",
            f"{report:.17g}",
        )
    )
    with path.open("x", encoding="utf-8") as stream:
        stream.write(header)
        stream.write(values)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe", action="store_true")
    parser.add_argument("--input")
    parser.add_argument("--mask")
    parser.add_argument("--output-dir")
    parser.add_argument("--model", default="mean")
    parser.add_argument("--nthreads", type=int, default=4)
    parser.add_argument("--seed", type=int, default=4401)
    parser.add_argument("--reference-index", type=int, default=0)
    parser.add_argument(
        "--court",
        choices=("common_resampler_estimation", "native_end_to_end"),
    )
    arguments = parser.parse_args(argv)

    ready, message = probe()
    if arguments.probe:
        print(message)
        return 0 if ready else 2
    if not ready:
        print(message, file=sys.stderr)
        return 2
    if arguments.model != "mean":
        print("only the registered mean model is supported", file=sys.stderr)
        return 2
    if not arguments.input or not arguments.output_dir or not arguments.court:
        print("--input, --output-dir, and --court are required", file=sys.stderr)
        return 2

    from nifreeze.registration.ants import (
        _prepare_registration_data,
        _run_registration,
    )
    from nitransforms.linear import LinearTransformsMapping
    from nitransforms.resampling import apply

    output_directory = Path(arguments.output_dir)
    decode_started = time.perf_counter()
    image = nb.load(arguments.input)
    data = np.asanyarray(image.dataobj)
    if data.ndim != 4 or data.shape[-1] < 2:
        print(f"expected rank-four input with at least two frames, got {data.shape}", file=sys.stderr)
        return 2
    if not 0 <= arguments.reference_index < data.shape[-1]:
        print("reference index is outside the input", file=sys.stderr)
        return 2
    affine = np.asarray(image.affine)
    header = image.header.copy()
    decode_seconds = time.perf_counter() - decode_started

    prepare_started = time.perf_counter()
    matrices = np.repeat(np.eye(4)[None, ...], data.shape[-1], axis=0)
    prepare_seconds = time.perf_counter() - prepare_started

    estimate_started = time.perf_counter()
    with tempfile.TemporaryDirectory(dir=output_directory) as temporary:
        work = Path(temporary)
        for frame in range(data.shape[-1]):
            if (
                arguments.court == "common_resampler_estimation"
                and frame == arguments.reference_index
            ):
                continue
            fixed = prediction(
                data,
                frame,
                arguments.reference_index,
                arguments.court,
            )
            fixed_path, moving_path, _ = _prepare_registration_data(
                data[..., frame],
                fixed,
                affine,
                frame,
                work,
                "both",
            )
            transform = _run_registration(
                fixed_path,
                moving_path,
                frame,
                work,
                fixedmask_path=arguments.mask,
                output_transform_prefix=f"ants-{frame:05d}",
                num_threads=arguments.nthreads,
                seed=arguments.seed,
            )
            matrices[frame] = transform.matrix
    estimate_seconds = time.perf_counter() - estimate_started

    apply_seconds: float | None = None
    encode_seconds: float | None = None
    if arguments.court == "native_end_to_end":
        apply_started = time.perf_counter()
        image_grid = type(
            "ImageGrid",
            (),
            {"shape": data.shape[:3], "affine": affine},
        )
        transforms = LinearTransformsMapping(matrices, reference=image_grid)
        corrected = np.empty_like(data)
        for frame, transform in enumerate(transforms):
            moving = nb.Nifti1Image(data[..., frame], affine, header)
            corrected[..., frame] = np.asanyarray(
                apply(transform, moving, order=3).dataobj,
                dtype=data.dtype,
            )
        apply_seconds = time.perf_counter() - apply_started

        encode_started = time.perf_counter()
        corrected_path = output_directory / "corrected.nii.gz"
        nb.Nifti1Image(corrected, affine, header).to_filename(corrected_path)
        with corrected_path.open("rb") as stream:
            os.fsync(stream.fileno())
        encode_seconds = time.perf_counter() - encode_started

    report_started = time.perf_counter()
    write_raw_matrices(output_directory / "raw-matrices.txt", matrices)
    report_seconds = time.perf_counter() - report_started
    write_stage_times(
        output_directory / "stage-times.tsv",
        decode_seconds,
        prepare_seconds,
        estimate_seconds,
        apply_seconds,
        encode_seconds,
        report_seconds,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
