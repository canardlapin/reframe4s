package reframe4s.benchmark.motion

import java.nio.file.Files
import java.nio.file.Path

final class SchedulerSuite extends munit.FunSuite:
  test("balanced plan contains every required method without silent skips"):
    val workload =
      Workload(
        "sub-01",
        "oblique",
        Path.of("/input.nii.gz"),
        Some(Path.of("/mask.nii.gz"))
      )
    val plan =
      right(
        BenchmarkScheduler.plan(
          Vector(workload),
          Path.of("/results"),
          warmupRuns = 1,
          measuredRuns = 4,
          allocatedCoreCounts = Vector(4),
          timeoutSeconds = 60L
        )
      )

    assertEquals(plan.size, 40)
    Court.values.foreach { court =>
      val measured =
        plan.filter(spec =>
          spec.court == court &&
            spec.phase == RunPhase.Measured
        )
      assertEquals(measured.size, 16)
      measured.groupBy(_.repetition).values.foreach { block =>
        assertEquals(
          block.map(_.implementation).toSet,
          Implementation.required.toSet
        )
        assertEquals(block.map(_.order).sorted, Vector(0, 1, 2, 3))
      }
      Implementation.required.foreach { implementation =>
        assertEquals(
          measured
            .filter(_.implementation == implementation)
            .map(_.order)
            .sorted,
          Vector(0, 1, 2, 3)
        )
      }
      val nifreeze =
        measured.filter(_.implementation == Implementation.Nifreeze)
      assert(
        nifreeze.forall(spec =>
          spec.effectiveWorkerCount == 4 &&
            spec.referencePolicy ==
              (if court == Court.NativeEndToEnd then
                 ReferencePolicy.MedianLeaveOneVolumeOut
               else ReferencePolicy.FixedFrame0)
        )
      )
      assert(
        measured
          .filter(_.implementation != Implementation.Nifreeze)
          .forall(spec =>
            spec.effectiveWorkerCount == 1 &&
              spec.referencePolicy == ReferencePolicy.FixedFrame0
          )
      )
    }

  test("the seeded order is deterministic"):
    val workload =
      Workload("s", "x", Path.of("/i"), None)
    val first =
      right(
        BenchmarkScheduler.plan(
          Vector(workload),
          Path.of("/o"),
          1,
          1,
          Vector(1),
          30L
        )
      )
    val second =
      right(
        BenchmarkScheduler.plan(
          Vector(workload),
          Path.of("/o"),
          1,
          1,
          Vector(1),
          30L
        )
      )
    assertEquals(first, second)

  test("plan round trip preserves and enforces execution registration"):
    val root = Files.createTempDirectory("reframe4s-plan")
    val plan =
      right(
        BenchmarkScheduler.plan(
          Vector(Workload("s", "x", Path.of("/i"), None)),
          root.resolve("runs"),
          0,
          1,
          Vector(1),
          30L
        )
      )
    val path = root.resolve("plan.tsv")
    right(PlanIo.writePlan(path, plan))
    assertEquals(right(PlanIo.readPlan(path)), plan)

    val invalid = root.resolve("invalid.tsv")
    val changed =
      Files
        .readString(path)
        .replaceFirst(
          "\timplementation_contract\tfixed_frame_0\t",
          "\timplementation_contract\tmedian_leave_one_volume_out\t"
        )
    val _ = Files.writeString(invalid, changed)
    assert(PlanIo.readPlan(invalid).isLeft)

  private def right[A](value: Either[RunnerError, A]): A =
    value.fold(error => fail(error.message), identity)
