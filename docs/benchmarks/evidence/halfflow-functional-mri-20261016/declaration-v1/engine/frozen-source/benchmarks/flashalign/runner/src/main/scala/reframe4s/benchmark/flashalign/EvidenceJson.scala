package reframe4s.benchmark.flashalign

object EvidenceJson:
  def render(record: RawPairResult): String =
    obj(
      "schema_version" -> string(record.schemaVersion),
      "run_id" -> string(record.runId),
      "case_id" -> string(record.pair.id),
      "subject_id" -> string(record.pair.subjectId),
      "cohort" -> string(record.pair.cohort.id),
      "method" -> obj(
        "id" -> string(record.method.id),
        "model" -> string(record.method.model),
        "revision" -> string(record.method.revision),
        "comparator_command" -> optionalString(record.method.comparatorCommand),
        "comparator_version" -> optionalString(record.method.comparatorVersion)
      ),
      "inputs" -> obj(
        "moving" -> string(record.pair.moving.sha256.hex),
        "fixed" -> string(record.pair.fixed.sha256.hex)
      ),
      "provenance" -> obj(
        "moving" -> artifact(record.pair.moving),
        "fixed" -> artifact(record.pair.fixed)
      ),
      "configuration" -> obj(
        "method" -> string(record.method.configurationSha256.hex),
        "sample_ids" -> string(record.pair.immutableSampleIdsSha256.hex)
      ),
      "environment" -> obj(
        "os" -> string(record.environment.os),
        "cpu" -> string(record.environment.cpu),
        "jdk" -> string(record.environment.jdk),
        "threads" -> record.environment.threads.toString
      ),
      "initialization" -> obj(
        "id" -> string(record.pair.initialization.id),
        "moving_to_fixed" -> array(record.pair.initialization.movingToFixed.map(number)),
        "sha256" -> string(record.pair.initialization.sha256.hex)
      ),
      "outcome" -> obj(
        "status" -> string(record.candidate.status.id),
        "accepted" -> record.candidate.accepted.toString,
        "failure_detail" -> record.candidate.failure
          .map(detail => obj("kind" -> string(detail.kind), "message" -> string(detail.message)))
          .getOrElse("null")
      ),
      "result" -> obj(
        "moving_to_fixed" -> record.candidate.movingToFixed
          .map(matrix => array(matrix.map(number)))
          .getOrElse("null"),
        "sha256" -> record.candidate.transformSha256
          .map(hash => string(hash.hex))
          .getOrElse("null")
      ),
      "last_checkpoint" -> record.candidate.lastCheckpoint
        .map(checkpoint =>
          obj(
            "moving_to_fixed" -> array(
              checkpoint.movingToFixed.map(number)
            ),
            "sha256" -> string(checkpoint.transformSha256.hex),
            "last_valid_moving_to_fixed" -> array(
              checkpoint.lastValidMovingToFixed.map(number)
            ),
            "last_valid_sha256" -> string(
              checkpoint.lastValidTransformSha256.hex
            ),
            "selection_objective" -> number(checkpoint.selectionObjective),
            "accepted_steps" -> checkpoint.acceptedSteps.toString,
            "selection_objective_id" -> checkpoint.selectionObjectiveId.toString,
            "initial_optimization_objective" -> number(
              checkpoint.initialOptimizationObjective
            ),
            "last_optimization_objective" -> number(
              checkpoint.lastOptimizationObjective
            )
          )
        )
        .getOrElse("null"),
      "metrics" -> metrics(record.metrics),
      "work" -> obj(
        "unique_interpolations" -> record.candidate.work.uniqueInterpolations.toString,
        "gradient_evaluations" -> record.candidate.work.gradientEvaluations.toString,
        "linearizations" -> record.candidate.work.linearizations.toString,
        "trial_evaluations" -> record.candidate.work.trialEvaluations.toString,
        "rejected_trials" -> record.candidate.work.rejectedTrials.toString,
        "early_rejections" -> record.candidate.work.earlyRejections.toString,
        "curvature_products" -> record.candidate.work.curvatureProducts.toString
      ),
      "timing_ms" -> obj(
        "read" -> number(record.timingMs.read),
        "decompress" -> number(record.timingMs.decompress),
        "prepare" -> number(record.timingMs.prepare),
        "capture" -> number(record.timingMs.capture),
        "optimize" -> number(record.timingMs.optimize),
        "validate" -> number(record.timingMs.validate),
        "output" -> number(record.timingMs.output),
        "total" -> number(record.timingMs.total)
      )
    )

  private def artifact(value: LicensedArtifact): String =
    obj(
      "path" -> string(value.path.toString),
      "sha256" -> string(value.sha256.hex),
      "provenance" -> string(value.provenance),
      "license" -> string(value.license)
    )

  private def metrics(value: PairMetrics): String =
    val independent = Vector(
      "landmark_rms_mm" -> optionalNumber(value.landmarkRmsMm),
      "landmark_p95_mm" -> optionalNumber(value.landmarkP95Mm),
      "landmark_max_mm" -> optionalNumber(value.landmarkMaximumMm)
    )
    val candidate = value.candidateMetrics.toVector.sortBy(_._1).map { case (key, metric) =>
      s"candidate_${key}" -> optionalNumber(metric)
    }
    obj((independent ++ candidate)*)

  private def optionalString(value: Option[String]): String = value.map(string).getOrElse("null")

  private def optionalNumber(value: Option[Double]): String = value.map(number).getOrElse("null")

  private def number(value: Double): String = java.lang.Double.toString(value)

  private def array(values: Vector[String]): String = values.mkString("[", ",", "]")

  private def obj(values: (String, String)*): String =
    values.map { case (key, value) => s"${string(key)}:$value" }.mkString("{", ",", "}")

  private def string(value: String): String =
    val escaped = value.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case character if character < ' ' => f"\\u${character.toInt}%04x"
      case character => character.toString
    }
    s"\"$escaped\""
