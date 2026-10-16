package reframe4s.benchmark.motion

import java.nio.file.Files
import java.nio.file.Path

final class ExternalAdapterSmokeSuite extends munit.FunSuite:
  test("nifreeze receives the mask only in its registered native court"):
    val root = Path.of("/benchmark")
    val workload =
      Workload(
        "sub",
        "scenario",
        root.resolve("input.nii.gz"),
        Some(root.resolve("mask.nii.gz"))
      )
    val config =
      AdapterConfig(
        "3dvolreg",
        "mcflirt",
        "python3",
        root.resolve("run_nifreeze.py"),
        "java",
        "candidate.jar",
        root
      )

    def command(court: Court): Vector[String] =
      val registration =
        right(
          ExecutionRegistration.create(
            Implementation.Nifreeze,
            court,
            allocatedCoreCount = 4
          )
        )
      val spec =
        RunSpec(
          workload,
          court,
          Implementation.Nifreeze,
          RunPhase.Measured,
          0,
          0,
          registration.allocatedCoreCount,
          registration.requestedWorkerCount,
          registration.effectiveWorkerCount,
          registration.effectiveWorkerEvidence,
          registration.referencePolicy,
          0,
          30L,
          root.resolve(court.id)
        )
      ImplementationAdapter.nifreeze
        .command(spec, RunLayout(spec.outputDirectory), config)
        .arguments

    assert(!command(Court.CommonResamplerEstimation).contains("--mask"))
    assert(command(Court.NativeEndToEnd).contains("--mask"))

  test("fake AFNI, nifreeze, and MCFLIRT tools emit complete successful rows"):
    val root = Files.createTempDirectory("reframe4s-adapter-smoke")
    val input = RunnerFixture.input(root)
    val afni = RunnerFixture.executable(root, "3dvolreg", afniScript)
    val mcflirt =
      RunnerFixture.executable(root, "mcflirt", mcflirtScript)
    val nifreeze = root.resolve("fake-nifreeze.py")
    val _ = Files.writeString(nifreeze, nifreezeScript)
    val java =
      Path
        .of(System.getProperty("java.home"))
        .resolve("bin")
        .resolve("java")
        .toString
    val config =
      AdapterConfig(
        afni.toString,
        mcflirt.toString,
        "python3",
        nifreeze,
        java,
        System.getProperty("java.class.path"),
        root
      )

    Court.values.foreach { court =>
      Vector(
        Implementation.Afni3dvolreg,
        Implementation.Nifreeze,
        Implementation.FslMcflirt
      ).zipWithIndex.foreach { case (implementation, index) =>
        val output = root.resolve(s"run-${court.id}-$index")
        val registration =
          right(
            ExecutionRegistration.create(
              implementation,
              court,
              allocatedCoreCount = 1
            )
          )
        val spec =
          RunSpec(
            Workload("sub-smoke", "identity", input, None),
            court,
            implementation,
            RunPhase.Measured,
            repetition = 0,
            order = index,
            allocatedCoreCount = registration.allocatedCoreCount,
            requestedWorkerCount = registration.requestedWorkerCount,
            effectiveWorkerCount = registration.effectiveWorkerCount,
            effectiveWorkerEvidence =
              registration.effectiveWorkerEvidence,
            referencePolicy = registration.referencePolicy,
            referenceIndex = 0,
            timeoutSeconds = 30L,
            output
          )
        val record =
          right(
            RunExecutor.execute(
              spec,
              ImplementationAdapter.forImplementation(implementation),
              config,
              "0" * 64
            )
          )

        assertEquals(record.termination, Termination.Success.id)
        assertEquals(record.exitStatus, 0)
        assert(record.poseSha256.nonEmpty)
        assertEquals(
          record.pipeline.timingIncludesFinalResampling,
          court == Court.NativeEndToEnd
        )
        if court == Court.CommonResamplerEstimation then
          assertEquals(
            record.pipeline.finalInterpolation,
            "image4s_reference_trilinear"
          )
        if court == Court.NativeEndToEnd then
          assert(record.correctedImageSha256.nonEmpty)
        assert(Files.isRegularFile(output.resolve("run.json")))
        assertEquals(
          right(
            EvidenceIo.readCanonicalPoses(output.resolve("poses.csv"))
          ).size,
          2
        )
      }
    }

  test("a comparator process failure remains a complete raw row"):
    val root = Files.createTempDirectory("reframe4s-adapter-failure")
    val input = RunnerFixture.input(root)
    val afni =
      RunnerFixture.executable(
        root,
        "3dvolreg-failure",
        """#!/bin/sh
          |if [ "$1" = "-help" ]; then
          |  echo "AFNI_26.1.04 fake"
          |  exit 0
          |fi
          |echo "registered failure" >&2
          |exit 7
          |""".stripMargin
      )
    val registration =
      right(
        ExecutionRegistration.create(
          Implementation.Afni3dvolreg,
          Court.NativeEndToEnd,
          allocatedCoreCount = 1
        )
      )
    val output = root.resolve("failed-run")
    val spec =
      RunSpec(
        Workload("sub-failure", "identity", input, None),
        Court.NativeEndToEnd,
        Implementation.Afni3dvolreg,
        RunPhase.Measured,
        0,
        0,
        registration.allocatedCoreCount,
        registration.requestedWorkerCount,
        registration.effectiveWorkerCount,
        registration.effectiveWorkerEvidence,
        registration.referencePolicy,
        0,
        30L,
        output
      )
    val java =
      Path
        .of(System.getProperty("java.home"))
        .resolve("bin")
        .resolve("java")
        .toString
    val config =
      AdapterConfig(
        afni.toString,
        "mcflirt",
        "python3",
        root.resolve("nifreeze.py"),
        java,
        System.getProperty("java.class.path"),
        root
      )
    val record =
      right(
        RunExecutor.execute(
          spec,
          ImplementationAdapter.afni,
          config,
          "0" * 64
        )
      )
    assertEquals(record.termination, Termination.ProcessFailure.id)
    assertEquals(record.exitStatus, 7)
    assert(record.failure.exists(_.contains("process exited 7")))
    assertEquals(record.poseSha256, None)
    assertEquals(record.correctedImageSha256, None)
    assert(record.materializedOutputBytes > 0L)
    assert(Files.isRegularFile(output.resolve("run.json")))

  private val afniScript =
    """#!/bin/sh
      |if [ "$1" = "-help" ]; then
      |  echo "AFNI_26.1.04 fake"
      |  exit 0
      |fi
      |raw=""
      |prefix=""
      |input=""
      |while [ "$#" -gt 0 ]; do
      |  case "$1" in
      |    -1Dmatrix_save) raw="$2"; shift 2 ;;
      |    -prefix) prefix="$2"; shift 2 ;;
      |    *.nii|*.nii.gz) input="$1"; shift ;;
      |    *) shift ;;
      |  esac
      |done
      |printf '1 0 0 0 0 1 0 0 0 0 1 0\n1 0 0 0 0 1 0 0 0 0 1 0\n' > "$raw"
      |if [ "$prefix" != "NULL" ]; then cp "$input" "$prefix"; fi
      |""".stripMargin

  private val mcflirtScript =
    """#!/bin/sh
      |if [ "$1" = "-help" ]; then
      |  echo "FSL 6.0.7.22 fake"
      |  exit 0
      |fi
      |input=""
      |output=""
      |while [ "$#" -gt 0 ]; do
      |  case "$1" in
      |    -in) input="$2"; shift 2 ;;
      |    -out) output="$2"; shift 2 ;;
      |    *) shift ;;
      |  esac
      |done
      |cp "$input" "${output}.nii.gz"
      |mkdir "${output}.mat"
      |printf '1 0 0 0\n0 1 0 0\n0 0 1 0\n0 0 0 1\n' > "${output}.mat/MAT_0000"
      |printf '1 0 0 0\n0 1 0 0\n0 0 1 0\n0 0 0 1\n' > "${output}.mat/MAT_0001"
      |""".stripMargin

  private val nifreezeScript =
    """import argparse
      |import pathlib
      |import shutil
      |
      |parser = argparse.ArgumentParser()
      |parser.add_argument("--probe", action="store_true")
      |parser.add_argument("--input")
      |parser.add_argument("--output-dir")
      |args, _ = parser.parse_known_args()
      |if args.probe:
      |    print("nifreeze pinned fake")
      |else:
      |    root = pathlib.Path(args.output_dir)
      |    shutil.copyfile(args.input, root / "corrected.nii.gz")
      |    (root / "raw-matrices.txt").write_text(
      |        "1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1\n"
      |        "1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1\n"
      |    )
      |    (root / "stage-times.tsv").write_text(
      |        "decode_seconds\tprepare_seconds\testimate_seconds\t"
      |        "apply_seconds\tencode_seconds\treport_seconds\n"
      |        "0.01\t0.01\t0.02\t0.01\t0.01\t0.01\n"
      |    )
      |""".stripMargin

  private def right[A](value: Either[RunnerError, A]): A =
    value.fold(error => fail(error.message), identity)
