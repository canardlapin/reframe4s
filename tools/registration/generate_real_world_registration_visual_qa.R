#!/usr/bin/env Rscript

# Generate fixed-space visual QA for one explicitly owned evidence bundle.
# Default: reframe4s HalfFlow fixtures. External HodgeFlow replay is opt-in
# through docs/benchmarks/manifests/external-hodgeflow-visual-qa-v1.json.
# The script replays only saved transforms/results. It never re-optimizes a
# registration or substitutes comparator output for a missing method output.

`%||%` <- function(left, right) if (is.null(left)) right else left

fail <- function(...) stop(paste0(...), call. = FALSE)

script_path <- sub("^--file=", "", grep("^--file=", commandArgs(FALSE), value = TRUE)[1])
repo_root <- normalizePath(file.path(dirname(script_path), "../.."), mustWork = TRUE)
arguments <- commandArgs(TRUE)
manifest_path <- if (length(arguments)) arguments[[1]] else file.path(
  repo_root,
  "docs/benchmarks/manifests/real-world-registration-visual-qa-v1.json"
)
manifest_path <- normalizePath(manifest_path, mustWork = TRUE)

required_packages <- c("digest", "jsonlite", "neuroim2")
missing_packages <- required_packages[!vapply(required_packages, requireNamespace, logical(1), quietly = TRUE)]
if (length(missing_packages)) {
  fail("missing required R packages: ", paste(missing_packages, collapse = ", "))
}

manifest <- jsonlite::read_json(manifest_path, simplifyVector = FALSE)
if (!identical(manifest$schema, "reframe4s-real-world-registration-visual-qa-manifest-v1")) {
  fail("unexpected visual-QA manifest schema")
}
external_reference <- identical(manifest$scope, "external-hodgeflow-reference")
if (!external_reference && !identical(manifest$scope, "reframe4s-methods")) {
  fail("visual-QA scope must name reframe4s methods or external HodgeFlow reference")
}
expected_courts <- if (external_reference) {
  c("hodgeflow-openneuro-ds000102-t1-to-mni", "hodgeflow-ibsr18-labeled-t1")
} else {
  c("basinbridge-public-t1-to-mni", "basinbridge-sub18-to-mni")
}
court_ids <- vapply(manifest$courts, `[[`, character(1), "courtId")
if (length(court_ids) != length(expected_courts) || !setequal(court_ids, expected_courts)) {
  fail("court ownership does not match visual-QA scope")
}
expected_owner <- if (external_reference) "external-hodgeflow" else "reframe4s"
expected_method <- if (external_reference) "HodgeFlow" else "HalfFlow"
for (spec in manifest$courts) {
  if (!identical(spec$methodOwner, expected_owner) || !identical(spec$methodId, expected_method)) {
    fail("method ownership does not match court: ", spec$courtId)
  }
}
expected_output_root <- if (external_reference) {
  "benchmarks/registration/external-references/hodgeflow/visual-qa/v1"
} else {
  "benchmarks/registration/visual-qa/v1"
}
if (!identical(manifest$outputRoot, expected_output_root) ||
    !identical(manifest$receipt, paste0(expected_output_root, "/receipt.json"))) {
  fail("output paths do not match visual-QA scope")
}
if (external_reference) {
  if (!requireNamespace("hodgeflow", quietly = TRUE)) fail("external replay requires hodgeflow")
  required_packages <- c(required_packages, "hodgeflow")
}
hodge_root <- Sys.getenv("HODGEFLOW_ROOT")
if (!nzchar(hodge_root)) hodge_root <- "/Users/bbuchsbaum/code/hodgeflow"
hodge_root <- normalizePath(hodge_root, mustWork = TRUE)
output_root <- file.path(repo_root, manifest$outputRoot)
dir.create(output_root, recursive = TRUE, showWarnings = FALSE)

repo_path <- function(path) {
  resolved <- file.path(repo_root, path)
  if (!file.exists(resolved)) fail("missing repository artifact: ", path)
  normalizePath(resolved, mustWork = TRUE)
}

hodge_path <- function(path) {
  resolved <- file.path(hodge_root, path)
  if (!file.exists(resolved)) fail("missing HodgeFlow artifact: ", path)
  normalizePath(resolved, mustWork = TRUE)
}

sha256 <- function(path) {
  digest::digest(file = path, algo = "sha256", serialize = FALSE)
}

relative_to <- function(path, root) {
  normalized <- normalizePath(path, mustWork = TRUE)
  prefix <- paste0(normalizePath(root, mustWork = TRUE), .Platform$file.sep)
  if (!startsWith(normalized, prefix)) fail("path is outside declared root: ", normalized)
  substring(normalized, nchar(prefix) + 1L)
}

artifact <- function(path, role, owner = c("hodgeflow", "repository")) {
  owner <- match.arg(owner)
  list(
    role = role,
    owner = owner,
    path = relative_to(path, if (owner == "hodgeflow") hodge_root else repo_root),
    bytes = unname(file.info(path)$size),
    sha256 = sha256(path)
  )
}

output_artifact <- function(path, role) {
  list(
    role = role,
    path = relative_to(path, repo_root),
    bytes = unname(file.info(path)$size),
    sha256 = sha256(path)
  )
}

read_neuro <- function(path) neuroim2::read_vol(path)
read_array <- function(path) as.array(read_neuro(path))

same_shape <- function(reference, candidate, label) {
  if (!identical(dim(reference), dim(candidate))) {
    fail(label, " shape ", paste(dim(candidate), collapse = "x"),
         " does not match fixed shape ", paste(dim(reference), collapse = "x"))
  }
  invisible(candidate)
}

clamp01 <- function(values) pmin(pmax(values, 0), 1)

normalize_volume <- function(volume) {
  values <- volume[is.finite(volume) & volume != 0]
  if (!length(values)) values <- volume[is.finite(volume)]
  if (!length(values)) fail("volume has no finite values")
  limits <- unname(stats::quantile(values, c(0.01, 0.99), na.rm = TRUE, names = FALSE))
  span <- max(limits[[2]] - limits[[1]], .Machine$double.eps)
  normalized <- clamp01((volume - limits[[1]]) / span)
  normalized[!is.finite(normalized)] <- NA_real_
  normalized
}

slice_plan <- function(mask) {
  coordinates <- which(mask & is.finite(mask), arr.ind = TRUE)
  if (!nrow(coordinates)) fail("fixed mask is empty")
  make_rows <- function(plane, axis) {
    indices <- as.integer(round(stats::quantile(
      coordinates[, axis], c(0.25, 0.50, 0.75), names = FALSE
    )))
    labels <- c("25%", "50%", "75%")
    Map(function(index, label) list(
      plane = plane,
      index = index,
      label = paste0(plane, " ", label, " (", index, ")")
    ), indices, labels)
  }
  c(make_rows("Axial", 3), make_rows("Coronal", 2), make_rows("Sagittal", 1))
}

slice_matrix <- function(volume, row) {
  matrix <- switch(
    row$plane,
    Axial = t(volume[, , row$index, drop = TRUE]),
    Coronal = t(volume[, row$index, , drop = TRUE]),
    Sagittal = t(volume[row$index, , , drop = TRUE]),
    fail("unknown plane: ", row$plane)
  )
  matrix[nrow(matrix):1, , drop = FALSE]
}

gray_raster <- function(matrix) {
  values <- clamp01(matrix)
  colors <- grDevices::gray(values)
  colors[!is.finite(matrix)] <- "#0F1218"
  as.raster(matrix(colors, nrow = nrow(matrix), ncol = ncol(matrix)))
}

gradient_magnitude <- function(matrix) {
  dx <- matrix(0, nrow(matrix), ncol(matrix))
  dy <- matrix(0, nrow(matrix), ncol(matrix))
  if (ncol(matrix) > 2) {
    dx[, 2:(ncol(matrix) - 1)] <- (
      matrix[, 3:ncol(matrix), drop = FALSE] -
        matrix[, 1:(ncol(matrix) - 2), drop = FALSE]
    ) / 2
  }
  if (nrow(matrix) > 2) {
    dy[2:(nrow(matrix) - 1), ] <- (
      matrix[3:nrow(matrix), , drop = FALSE] -
        matrix[1:(nrow(matrix) - 2), , drop = FALSE]
    ) / 2
  }
  sqrt(dx * dx + dy * dy)
}

scale_edge <- function(edge) {
  finite <- edge[is.finite(edge) & edge > 0]
  if (!length(finite)) return(edge * 0)
  scale <- unname(stats::quantile(finite, 0.95, names = FALSE))
  clamp01(edge / max(scale, .Machine$double.eps))
}

edge_raster <- function(fixed, candidate, mask = NULL) {
  fixed_edge <- gradient_magnitude(fixed)
  candidate_edge <- gradient_magnitude(candidate)
  if (!is.null(mask)) {
    fixed_edge[!mask] <- 0
    candidate_edge[!mask] <- 0
  }
  fixed_edge <- scale_edge(fixed_edge)
  candidate_edge <- scale_edge(candidate_edge)
  base <- 0.08 * clamp01(fixed)
  red <- pmin(base + candidate_edge, 1)
  green <- pmin(base + fixed_edge, 1)
  blue <- pmin(base + fixed_edge, 1)
  colors <- grDevices::rgb(as.vector(red), as.vector(green), as.vector(blue))
  colors[!is.finite(as.vector(fixed)) | !is.finite(as.vector(candidate))] <- "#0F1218"
  as.raster(matrix(colors, nrow = nrow(fixed), ncol = ncol(fixed)))
}

jacobian_raster <- function(jacobian, mask = NULL) {
  limit <- log(2)
  log_jacobian <- log(jacobian)
  scaled <- clamp01((log_jacobian + limit) / (2 * limit))
  palette <- grDevices::colorRampPalette(c("#3B4CC0", "#F7F7F7", "#B40426"))(256)
  index <- pmax(1, pmin(256, as.integer(round(1 + 255 * scaled))))
  colors <- palette[index]
  colors[!is.finite(jacobian) | jacobian <= 0] <- "#FF00FF"
  if (!is.null(mask)) colors[!as.vector(mask)] <- "#0F1218"
  as.raster(matrix(colors, nrow = nrow(jacobian), ncol = ncol(jacobian)))
}

draw_raster <- function(raster, title = "", row_label = NULL) {
  graphics::plot.new()
  graphics::plot.window(xlim = c(0, ncol(raster)), ylim = c(0, nrow(raster)), asp = 1)
  graphics::rasterImage(raster, 0, 0, ncol(raster), nrow(raster), interpolate = FALSE)
  graphics::box(col = "#465064", lwd = 0.6)
  if (nzchar(title)) graphics::title(main = title, col.main = "#E9EDF5", cex.main = 0.82, line = 0.3)
  if (!is.null(row_label)) {
    graphics::mtext(row_label, side = 2, col = "#B8C0CF", cex = 0.65, line = 0.1)
  }
}

draw_status <- function(message, title = "", row_label = NULL, label = "NO SAFE\nMETHOD OUTPUT") {
  graphics::plot.new()
  graphics::plot.window(xlim = c(0, 1), ylim = c(0, 1), asp = 1)
  graphics::rect(0, 0, 1, 1, col = "#2B171B", border = "#C94752", lwd = 1.5)
  graphics::text(0.5, 0.56, label, col = "#FF9AA2", cex = 0.9, font = 2)
  graphics::text(0.5, 0.25, message, col = "#D8A8AD", cex = 0.55)
  if (nzchar(title)) graphics::title(main = title, col.main = "#E9EDF5", cex.main = 0.82, line = 0.3)
  if (!is.null(row_label)) {
    graphics::mtext(row_label, side = 2, col = "#B8C0CF", cex = 0.65, line = 0.1)
  }
}

open_png <- function(path, columns, rows) {
  dir.create(dirname(path), recursive = TRUE, showWarnings = FALSE)
  grDevices::png(
    path,
    width = max(1200, 238 * columns),
    height = max(1100, 174 * rows + 180),
    res = 130,
    bg = "#0F1218",
    type = if (capabilities("cairo")) "cairo" else getOption("bitmapType")
  )
}

render_plate <- function(path, title, subtitle, fixed, mask, columns) {
  rows <- slice_plan(mask)
  open_png(path, length(columns), length(rows))
  on.exit(grDevices::dev.off(), add = TRUE)
  graphics::par(
    mfrow = c(length(rows), length(columns)),
    mar = c(0.15, 0.55, 1.15, 0.1),
    oma = c(1.0, 0.5, 4.6, 0.4),
    bg = "#0F1218"
  )
  for (row_index in seq_along(rows)) {
    row <- rows[[row_index]]
    fixed_slice <- slice_matrix(fixed, row)
    mask_slice <- slice_matrix(mask, row)
    for (column_index in seq_along(columns)) {
      column <- columns[[column_index]]
      heading <- if (row_index == 1) column$label else ""
      row_label <- if (column_index == 1) row$label else NULL
      if (column$kind == "gray") {
        draw_raster(gray_raster(slice_matrix(column$volume, row)), heading, row_label)
      } else if (column$kind == "edge") {
        candidate <- slice_matrix(column$volume, row)
        draw_raster(edge_raster(fixed_slice, candidate, mask_slice), heading, row_label)
      } else if (column$kind == "jacobian") {
        draw_raster(
          jacobian_raster(slice_matrix(column$volume, row), mask_slice),
          heading,
          row_label
        )
      } else if (column$kind == "status") {
        draw_status(column$message, heading, row_label, column$statusLabel %||% "NO SAFE\nMETHOD OUTPUT")
      } else {
        fail("unknown panel kind: ", column$kind)
      }
    }
  }
  graphics::mtext(title, side = 3, outer = TRUE, col = "#F5F7FA", cex = 1.35, font = 2, line = 2.5)
  graphics::mtext(subtitle, side = 3, outer = TRUE, col = "#B8C0CF", cex = 0.82, line = 0.9)
  graphics::mtext(
    "Nine fixed-space slices: 25%, 50%, and 75% along each anatomical axis",
    side = 1, outer = TRUE, col = "#8993A4", cex = 0.68, line = 0.1
  )
  grDevices::dev.off()
  on.exit(NULL, add = FALSE)
  invisible(path)
}

center_axial_row <- function(mask) slice_plan(mask)[[2]]

contact_item <- function(case_id, fixed, candidate = NULL, jacobian = NULL, comparator = NULL, mask) {
  row <- center_axial_row(mask)
  fixed_slice <- slice_matrix(fixed, row)
  mask_slice <- slice_matrix(mask, row)
  list(
    caseId = case_id,
    candidate = if (is.null(candidate)) NULL else edge_raster(
      fixed_slice, slice_matrix(candidate, row), mask_slice
    ),
    comparator = if (is.null(comparator)) NULL else edge_raster(
      fixed_slice, slice_matrix(comparator, row), mask_slice
    ),
    jacobian = if (is.null(jacobian)) NULL else jacobian_raster(
      slice_matrix(jacobian, row), mask_slice
    )
  )
}

render_contact <- function(path, title, records, labels) {
  open_png(path, length(labels), length(records))
  on.exit(grDevices::dev.off(), add = TRUE)
  graphics::par(
    mfrow = c(length(records), length(labels)),
    mar = c(0.08, 0.65, 1.1, 0.08),
    oma = c(0.8, 0.4, 3.4, 0.2),
    bg = "#0F1218"
  )
  for (row_index in seq_along(records)) {
    record <- records[[row_index]]
    for (column_index in seq_along(labels)) {
      key <- names(labels)[[column_index]]
      heading <- if (row_index == 1) unname(labels[[column_index]]) else ""
      raster <- record[[key]]
      if (is.null(raster)) {
        draw_status(
          "retained metrics only", heading,
          if (column_index == 1) record$caseId else NULL,
          "OUTPUT NOT\nLOCAL"
        )
      } else {
        draw_raster(raster, heading, if (column_index == 1) record$caseId else NULL)
      }
    }
  }
  graphics::mtext(title, side = 3, outer = TRUE, col = "#F5F7FA", cex = 1.35, font = 2, line = 1.7)
  graphics::mtext(
    "Center axial fixed-space slice; fixed edges cyan and candidate edges red",
    side = 1, outer = TRUE, col = "#8993A4", cex = 0.68, line = 0.1
  )
  grDevices::dev.off()
  on.exit(NULL, add = FALSE)
  invisible(path)
}

ncc <- function(left, right, mask = NULL) {
  valid <- is.finite(left) & is.finite(right)
  if (!is.null(mask)) valid <- valid & mask
  if (sum(valid) < 2) return(NA_real_)
  stats::cor(left[valid], right[valid])
}

near <- function(actual, expected, tolerance = 1e-8, label = "value") {
  if (!isTRUE(all.equal(as.numeric(actual), as.numeric(expected), tolerance = tolerance))) {
    fail(label, " differs: ", actual, " versus ", expected)
  }
}

jacobian_summary <- function(jacobian, mask) {
  values <- jacobian[mask & is.finite(jacobian)]
  list(
    finiteVoxels = length(values),
    nonPositiveVoxels = sum(values <= 0),
    belowHalfPercent = 100 * mean(values < 0.5),
    aboveTwoPercent = 100 * mean(values > 2)
  )
}

court_specs <- setNames(manifest$courts, vapply(manifest$courts, `[[`, character(1), "courtId"))
case_receipts <- list()
court_outputs <- list()

# Direct BasinBridge T1-to-template smoke court: input geometry only because no
# complete transform or registered volume was emitted.
if (!external_reference) {
public_spec <- court_specs[["basinbridge-public-t1-to-mni"]]
public_moving_path <- hodge_path(public_spec$moving)
public_fixed_path <- hodge_path(public_spec$fixed)
public_mask_path <- hodge_path(public_spec$fixedMask)
public_receipt_path <- repo_path(public_spec$localReceipt)
public_moving <- read_neuro(public_moving_path)
public_fixed_neuro <- read_neuro(public_fixed_path)
public_fixed <- normalize_volume(as.array(public_fixed_neuro))
public_baseline <- normalize_volume(as.array(neuroim2::resample_to(public_moving, public_fixed_neuro)))
public_mask <- read_array(public_mask_path) > 0
same_shape(public_fixed, public_baseline, "public baseline")
same_shape(public_fixed, public_mask, "public mask")
public_result <- jsonlite::read_json(public_receipt_path, simplifyVector = TRUE)
public_png <- file.path(output_root, "basinbridge-public-t1-input-only.png")
render_plate(
  public_png,
  "BasinBridge public T1 to MNI — input QA only",
  sprintf(
    "Bridge match error %.2f → %.2f mm; the test retained no registered volume",
    public_result$observed$initialMatchErrorMm,
    public_result$observed$finalMatchErrorMm
  ),
  public_fixed,
  public_mask,
  list(
    list(label = "Fixed MNI", kind = "gray", volume = public_fixed),
    list(label = "Header/world baseline", kind = "gray", volume = public_baseline),
    list(label = "Baseline edges", kind = "edge", volume = public_baseline),
    list(label = "BasinBridge result", kind = "status", message = "stage did not export image")
  )
)
case_receipts[[public_spec$caseId]] <- list(
  courtId = public_spec$courtId,
  caseId = public_spec$caseId,
  qaStatus = "input-geometry-rendered-method-output-unavailable",
  methodStatus = public_spec$methodStatus,
  sources = list(
    artifact(public_moving_path, "moving"),
    artifact(public_fixed_path, "fixed"),
    artifact(public_mask_path, "fixed-mask"),
    artifact(public_receipt_path, "result-receipt", "repository")
  ),
  outputs = list(output_artifact(public_png, "detailed-plate"))
)

# Retained sub18 HalfFlow failure: render the input baseline and saved ANTs
# comparator, while leaving the method column explicitly empty.
sub18_spec <- court_specs[["basinbridge-sub18-to-mni"]]
sub18_dir <- hodge_path(sub18_spec$directory)
sub18_fixed_path <- file.path(sub18_dir, "fixed.nii.gz")
sub18_moving_path <- file.path(sub18_dir, "moving.nii.gz")
sub18_mask_path <- file.path(sub18_dir, "fixed_mask.nii.gz")
sub18_ants_path <- file.path(sub18_dir, sub18_spec$comparator)
sub18_receipt_path <- repo_path(sub18_spec$localReceipt)
sub18_fixed_neuro <- read_neuro(sub18_fixed_path)
sub18_fixed <- normalize_volume(as.array(sub18_fixed_neuro))
sub18_baseline <- normalize_volume(as.array(neuroim2::resample_to(
  read_neuro(sub18_moving_path), sub18_fixed_neuro
)))
sub18_ants <- normalize_volume(read_array(sub18_ants_path))
sub18_mask <- read_array(sub18_mask_path) > 0
same_shape(sub18_fixed, sub18_baseline, "sub18 baseline")
same_shape(sub18_fixed, sub18_ants, "sub18 ANTs")
same_shape(sub18_fixed, sub18_mask, "sub18 mask")
sub18_result <- jsonlite::read_json(sub18_receipt_path, simplifyVector = TRUE)
sub18_png <- file.path(output_root, "basinbridge-sub18-retained-failure.png")
render_plate(
  sub18_png,
  "HalfFlow sub18 to MNI — retained failure QA",
  sprintf(
    "HalfFlow: %d non-positive Jacobians, min %.3f; saved ANTs mask Dice %.3f",
    sub18_result$C4$intermediate_bridge_diagnostics$nonpositive_sampled_jacobians,
    sub18_result$C4$intermediate_bridge_diagnostics$minimum_sampled_jacobian,
    sub18_result$saved_ants_oracle$observed$brain_mask_dice
  ),
  sub18_fixed,
  sub18_mask,
  list(
    list(label = "Fixed MNI", kind = "gray", volume = sub18_fixed),
    list(label = "Header/world baseline", kind = "gray", volume = sub18_baseline),
    list(label = "HalfFlow result", kind = "status", message = "topology/export rejected"),
    list(label = "Saved ANTs", kind = "gray", volume = sub18_ants),
    list(label = "Baseline edges", kind = "edge", volume = sub18_baseline),
    list(label = "ANTs edges", kind = "edge", volume = sub18_ants)
  )
)
case_receipts[[sub18_spec$caseId]] <- list(
  courtId = sub18_spec$courtId,
  caseId = sub18_spec$caseId,
  qaStatus = "failure-visible-comparator-rendered-method-output-withheld",
  methodStatus = sub18_spec$methodStatus,
  sources = list(
    artifact(sub18_moving_path, "moving"),
    artifact(sub18_fixed_path, "fixed"),
    artifact(sub18_mask_path, "fixed-mask"),
    artifact(sub18_ants_path, "saved-ants-comparator"),
    artifact(sub18_receipt_path, "result-receipt", "repository")
  ),
  outputs = list(output_artifact(sub18_png, "detailed-plate"))
)

}

# External reference only: never used to fill a reframe4s method column.
if (external_reference) {
# Six-case prospective HodgeFlow T1-to-MNI screen.
open_spec <- court_specs[["hodgeflow-openneuro-ds000102-t1-to-mni"]]
open_dir <- hodge_path(open_spec$directory)
open_manifest_path <- file.path(open_dir, open_spec$manifest)
open_metrics_path <- file.path(open_dir, open_spec$metrics)
open_manifest <- utils::read.csv(open_manifest_path, stringsAsFactors = FALSE)
open_metrics <- utils::read.csv(open_metrics_path, stringsAsFactors = FALSE)
open_contacts <- list()
for (case_id in unlist(open_spec$caseIds)) {
  row <- open_manifest[open_manifest$case == case_id, , drop = FALSE]
  metric <- open_metrics[open_metrics$case == case_id, , drop = FALSE]
  if (nrow(row) != 1 || nrow(metric) != 1) fail("OpenNeuro case row missing: ", case_id)
  rds_matches <- list.files(
    file.path(open_dir, "results"),
    pattern = paste0("registration_", case_id, "\\.rds$"),
    recursive = TRUE,
    full.names = TRUE
  )
  if (length(rds_matches) != 1) fail("OpenNeuro registration RDS not unique: ", case_id)
  moving_path <- normalizePath(row$moving_path, mustWork = TRUE)
  fixed_path <- normalizePath(row$fixed_path, mustWork = TRUE)
  mask_path <- normalizePath(row$fixed_mask_path, mustWork = TRUE)
  fixed_neuro <- read_neuro(fixed_path)
  fixed <- normalize_volume(as.array(fixed_neuro))
  baseline <- normalize_volume(as.array(neuroim2::resample_to(read_neuro(moving_path), fixed_neuro)))
  result <- readRDS(rds_matches[[1]])
  registered <- normalize_volume(result$warped_moving)
  jacobian <- result$jac_det_total_pullback
  mask <- read_array(mask_path) > 0
  same_shape(fixed, baseline, paste(case_id, "baseline"))
  same_shape(fixed, registered, paste(case_id, "registered"))
  same_shape(fixed, jacobian, paste(case_id, "jacobian"))
  same_shape(fixed, mask, paste(case_id, "mask"))
  near(
    min(jacobian[mask], na.rm = TRUE),
    metric$min_jac_det_total_pullback_fixed_mask,
    tolerance = 1e-8,
    label = paste(case_id, "minimum fixed-mask Jacobian")
  )
  observed_ncc <- ncc(fixed, registered, mask)
  plate_path <- file.path(output_root, "openneuro-ds000102", paste0(case_id, ".png"))
  render_plate(
    plate_path,
    paste0(case_id, " — HodgeFlow T1 to MNI"),
    sprintf(
      "masked NCC %.3f → %.3f; global NCC %.3f → %.3f; min fixed-mask J %.3f",
      metric$ncc_pre, metric$ncc_post, metric$global_ncc_pre, metric$global_ncc_post,
      metric$min_jac_det_total_pullback_fixed_mask
    ),
    fixed,
    mask,
    list(
      list(label = "Fixed MNI", kind = "gray", volume = fixed),
      list(label = "Header/world baseline", kind = "gray", volume = baseline),
      list(label = "HodgeFlow", kind = "gray", volume = registered),
      list(label = "Baseline edges", kind = "edge", volume = baseline),
      list(label = "HodgeFlow edges", kind = "edge", volume = registered),
      list(label = "log total Jacobian", kind = "jacobian", volume = jacobian)
    )
  )
  open_contacts[[length(open_contacts) + 1L]] <- contact_item(
    case_id, fixed, registered, jacobian = jacobian, mask = mask
  )
  case_receipts[[case_id]] <- list(
    courtId = open_spec$courtId,
    caseId = case_id,
    qaStatus = "registered-output-and-jacobian-rendered",
    observed = list(
      maskedNccReceipt = metric$ncc_post,
      maskedNccReplay = observed_ncc,
      globalNccReceipt = metric$global_ncc_post,
      minimumFixedMaskJacobian = metric$min_jac_det_total_pullback_fixed_mask,
      jacobianDisplayTails = jacobian_summary(jacobian, mask)
    ),
    sources = list(
      artifact(moving_path, "moving"),
      artifact(fixed_path, "fixed"),
      artifact(mask_path, "fixed-mask"),
      artifact(rds_matches[[1]], "registration-result")
    ),
    outputs = list(output_artifact(plate_path, "detailed-plate"))
  )
}
open_contact_path <- file.path(output_root, "openneuro-ds000102-contact.png")
render_contact(
  open_contact_path,
  "OpenNeuro ds000102 — HodgeFlow cohort contact sheet",
  open_contacts,
  c(candidate = "HodgeFlow edges", jacobian = "log total Jacobian")
)
court_outputs[[open_spec$courtId]] <- list(output_artifact(open_contact_path, "contact-sheet"))

# Nine labeled IBSR18 pairs with affine, HodgeFlow, and ANTs outputs. Reapply
# the saved portable HodgeFlow transform to the moving intensity image.
ibsr_spec <- court_specs[["hodgeflow-ibsr18-labeled-t1"]]
ibsr_dir <- hodge_path(ibsr_spec$directory)
ibsr_metrics_path <- file.path(ibsr_dir, ibsr_spec$metrics)
ibsr_metadata_dir <- file.path(ibsr_dir, ibsr_spec$metadataDirectory)
ibsr_archive_path <- file.path(ibsr_dir, ibsr_spec$localArchive)
ibsr_metrics <- utils::read.csv(ibsr_metrics_path, stringsAsFactors = FALSE)
ibsr_contacts <- list()
ibsr_method_outputs_rendered <- 0L
for (pair_id in unlist(ibsr_spec$pairIds)) {
  pair_dir <- file.path(ibsr_dir, "runs", pair_id)
  spec_path <- file.path(pair_dir, "hodgeflow", "spec.json")
  local_result_path <- file.path(pair_dir, "hodgeflow", "result.json")
  metadata_result_path <- file.path(ibsr_metadata_dir, pair_id, "hodgeflow", "result.json")
  result_path <- if (file.exists(local_result_path)) local_result_path else metadata_result_path
  pair_spec <- jsonlite::read_json(spec_path, simplifyVector = TRUE)
  pair_result <- jsonlite::read_json(result_path, simplifyVector = TRUE)
  moving_path <- normalizePath(pair_spec$moving, mustWork = TRUE)
  fixed_path <- normalizePath(pair_spec$fixed, mustWork = TRUE)
  mask_path <- normalizePath(pair_spec$fixed_mask, mustWork = TRUE)
  affine_path <- file.path(pair_dir, "affine", "tx_Warped.nii.gz")
  ants_path <- file.path(pair_dir, "ants", "tx_Warped.nii.gz")
  transform_prefix <- file.path(pair_dir, "hodgeflow", "tx")
  transform_paths <- c(
    paste0(transform_prefix, "_0GenericAffine.tfm"),
    paste0(transform_prefix, "_1Warp.nii.gz"),
    paste0(transform_prefix, "_transform.json")
  )
  has_affine <- file.exists(affine_path)
  has_ants <- file.exists(ants_path)
  has_hodgeflow <- all(file.exists(transform_paths))
  fixed <- normalize_volume(read_array(fixed_path))
  mask <- read_array(mask_path) > 0
  affine <- if (has_affine) normalize_volume(read_array(affine_path)) else NULL
  ants <- if (has_ants) normalize_volume(read_array(ants_path)) else NULL
  hodgeflow_warped <- NULL
  jacobian <- NULL
  if (has_hodgeflow) {
    transform <- hodgeflow::hf_read_transform(transform_prefix, reference = fixed_path)
    hodgeflow_warped <- normalize_volume(hodgeflow::hf_apply_transform(
      moving_path, transform, interpolation = "linear"
    ))
    jacobian <- hodgeflow::hf_transform_jacobian(transform)$total_pullback
    ibsr_method_outputs_rendered <- ibsr_method_outputs_rendered + 1L
  }
  same_shape(fixed, mask, paste(pair_id, "mask"))
  if (has_affine) same_shape(fixed, affine, paste(pair_id, "affine"))
  if (has_ants) same_shape(fixed, ants, paste(pair_id, "ANTs"))
  if (has_hodgeflow) {
    same_shape(fixed, hodgeflow_warped, paste(pair_id, "HodgeFlow"))
    same_shape(fixed, jacobian, paste(pair_id, "jacobian"))
    near(
      min(jacobian, na.rm = TRUE), pair_result$jacobian$min,
      tolerance = 1e-7, label = paste(pair_id, "minimum Jacobian")
    )
  }
  metrics <- ibsr_metrics[ibsr_metrics$pair == pair_id, , drop = FALSE]
  if (!setequal(metrics$method, c("affine", "hodgeflow", "ants"))) {
    fail("IBSR metric lanes missing: ", pair_id)
  }
  metric_for <- function(method) metrics[metrics$method == method, , drop = FALSE]
  affine_metric <- metric_for("affine")
  hodge_metric <- metric_for("hodgeflow")
  ants_metric <- metric_for("ants")
  missing_column <- function(label, message = "retained metrics only") list(
    label = label,
    kind = "status",
    statusLabel = "OUTPUT NOT\nLOCAL",
    message = message
  )
  plate_path <- file.path(output_root, "ibsr18", paste0(pair_id, ".png"))
  render_plate(
    plate_path,
    paste0(pair_id, " — labeled T1 anatomical QA"),
    sprintf(
      "Dice affine %.3f, HodgeFlow %.3f, ANTs %.3f; HD95 HodgeFlow %.2f mm, ANTs %.2f mm",
      affine_metric$dice, hodge_metric$dice, ants_metric$dice,
      hodge_metric$hd95_mm, ants_metric$hd95_mm
    ),
    fixed,
    mask,
    list(
      list(label = "Fixed T1", kind = "gray", volume = fixed),
      if (has_affine) list(label = "Shared affine", kind = "gray", volume = affine) else
        missing_column("Shared affine"),
      if (has_hodgeflow) list(label = "HodgeFlow", kind = "gray", volume = hodgeflow_warped) else
        missing_column("HodgeFlow"),
      if (has_ants) list(label = "ANTs SyN", kind = "gray", volume = ants) else
        missing_column("ANTs SyN"),
      if (has_hodgeflow) list(label = "HodgeFlow edges", kind = "edge", volume = hodgeflow_warped) else
        missing_column("HodgeFlow edges"),
      if (has_ants) list(label = "ANTs edges", kind = "edge", volume = ants) else
        missing_column("ANTs edges"),
      if (has_hodgeflow) list(label = "log total Jacobian", kind = "jacobian", volume = jacobian) else
        missing_column("log total Jacobian")
    )
  )
  ibsr_contacts[[length(ibsr_contacts) + 1L]] <- contact_item(
    pair_id, fixed, hodgeflow_warped, jacobian = jacobian, comparator = ants, mask = mask
  )
  case_receipts[[pair_id]] <- list(
    courtId = ibsr_spec$courtId,
    caseId = pair_id,
    qaStatus = if (has_hodgeflow) {
      "affine-hodgeflow-ants-and-jacobian-rendered"
    } else {
      "fixed-and-available-comparators-rendered-method-transform-not-local"
    },
    localOutputAvailability = list(
      affine = has_affine,
      hodgeflow = has_hodgeflow,
      ants = has_ants
    ),
    observed = list(
      affineDice = affine_metric$dice,
      hodgeflowDice = hodge_metric$dice,
      antsDice = ants_metric$dice,
      hodgeflowHd95Mm = hodge_metric$hd95_mm,
      antsHd95Mm = ants_metric$hd95_mm,
      minimumJacobian = pair_result$jacobian$min,
      jacobianDisplayTails = if (has_hodgeflow) jacobian_summary(jacobian, mask) else NULL
    ),
    sources = Filter(Negate(is.null), list(
      artifact(moving_path, "moving"),
      artifact(fixed_path, "fixed"),
      artifact(mask_path, "fixed-mask"),
      artifact(spec_path, "method-spec"),
      artifact(result_path, "method-result"),
      artifact(ibsr_metrics_path, "cohort-metrics"),
      artifact(ibsr_archive_path, "local-result-archive"),
      if (has_hodgeflow) artifact(transform_paths[[1]], "hodgeflow-affine") else NULL,
      if (has_hodgeflow) artifact(transform_paths[[2]], "hodgeflow-warp") else NULL,
      if (has_hodgeflow) artifact(transform_paths[[3]], "hodgeflow-transform-manifest") else NULL,
      if (has_affine) artifact(affine_path, "shared-affine-warped") else NULL,
      if (has_ants) artifact(ants_path, "ants-warped") else NULL
    )),
    outputs = list(output_artifact(plate_path, "detailed-plate"))
  )
}
ibsr_contact_path <- file.path(output_root, "ibsr18-contact.png")
render_contact(
  ibsr_contact_path,
  "IBSR18 — HodgeFlow and ANTs cohort contact sheet",
  ibsr_contacts,
  c(candidate = "HodgeFlow edges", comparator = "ANTs edges", jacobian = "log total Jacobian")
)
court_outputs[[ibsr_spec$courtId]] <- list(output_artifact(ibsr_contact_path, "contact-sheet"))
}

case_receipts <- lapply(case_receipts, function(record) {
  record$methodOwner <- court_specs[[record$courtId]]$methodOwner
  record$methodId <- court_specs[[record$courtId]]$methodId
  record
})

receipt_path <- file.path(repo_root, manifest$receipt)
generator_path <- normalizePath(script_path, mustWork = TRUE)
receipt <- list(
  schema = "reframe4s-real-world-registration-visual-qa-receipt-v1",
  scope = manifest$scope,
  capturedAt = manifest$capturedAt,
  manifest = artifact(manifest_path, "visual-qa-manifest", "repository"),
  generator = artifact(generator_path, "visual-qa-generator", "repository"),
  environment = list(
    r = R.version.string,
    packages = as.list(stats::setNames(
      vapply(required_packages, function(package) as.character(utils::packageVersion(package)), character(1)),
      required_packages
    ))
  ),
  renderingContract = manifest$contract,
  coverage = if (!external_reference) list(
    directReframeCases = 2,
    directReframeMethodOutputsRendered = 0,
    directReframeMissingOrRejectedOutputsRetained = 2,
    detailedPlates = length(case_receipts),
    contactSheets = 0
  ) else list(
    externalHodgeFlowCases = 15,
    externalHodgeFlowMethodOutputsRendered = 6L + ibsr_method_outputs_rendered,
    externalHodgeFlowMetricRowsWithoutLocalMethodOutput = 9L - ibsr_method_outputs_rendered,
    detailedPlates = length(case_receipts),
    contactSheets = 2
  ),
  courtOutputs = if (length(court_outputs)) court_outputs else setNames(list(), character()),
  cases = unname(case_receipts),
  claimBoundary = paste(
    "These plates are sampled human-facing diagnostics over nine fixed-space slices per case.",
    "They can reveal gross misregistration and deformation artifacts but do not replace the",
    "frozen quantitative courts or establish reframe4s acquired-data accuracy."
  )
)
dir.create(dirname(receipt_path), recursive = TRUE, showWarnings = FALSE)
jsonlite::write_json(
  receipt, receipt_path, auto_unbox = TRUE, pretty = TRUE, null = "null", digits = 17
)
cat(
  sprintf(
    "real-world visual QA generated: %d detailed plates, %d contact sheets; receipt %s\n",
    receipt$coverage$detailedPlates,
    receipt$coverage$contactSheets,
    relative_to(receipt_path, repo_root)
  )
)
