package mill


import ammonite.ops._
import ImplicitWd._
import mill.define.Task
import mill.discover.Discovered
import mill.eval.{Evaluator, PathRef}
import mill.modules.Jvm.jarUp
import mill.util.OSet
import utest._

object JavaCompileJarTests extends TestSuite{
  def compileAll(dest: Path, sources: Seq[PathRef]) = {
    mkdir(dest)
    import ammonite.ops._
    %("javac", sources.map(_.path.toString()), "-d", dest)(wd = dest)
    PathRef(dest)
//
  }



  val tests = Tests{
    'javac {
      val workspacePath = pwd / 'target / 'workspace / 'javac
      val javacSrcPath = pwd / 'core / 'src / 'test / 'examples / 'javac
      val javacDestPath = workspacePath / 'src

      mkdir(pwd / 'target / 'workspace / 'javac)
      cp(javacSrcPath, javacDestPath)

      object Build extends Task.Cacher{
        def sourceRootPath = javacDestPath / 'src
        def resourceRootPath = javacDestPath / 'resources

        // sourceRoot -> allSources -> classFiles
        //                                |
        //                                v
        //           resourceRoot ---->  jar
        def sourceRoot = T{ Task.path(sourceRootPath) }
        def resourceRoot = T{ Task.path(resourceRootPath) }
        def allSources = T{ ls.rec(sourceRoot().path).map(PathRef(_)) }
        def classFiles = T{ compileAll(Task.ctx().dest, allSources()) }
        def jar = T{ jarUp(resourceRoot, classFiles) }

        def run(mainClsName: String) = T.command{
          %%('java, "-cp", classFiles().path, mainClsName)
        }
      }
      import Build._
      val mapping = Discovered.mapping(Build)

      def eval[T](t: Task[T]): (T, Int) = {
        val evaluator = new Evaluator(workspacePath, mapping)
        val evaluated = evaluator.evaluate(OSet(t))
        Tuple2(
          evaluated.values(0).asInstanceOf[T],
          evaluated.targets.filter(x => mapping.contains(x) || x.isInstanceOf[mill.define.Command[_]]).size
        )
      }
      def check(targets: OSet[Task[_]], expected: OSet[Task[_]]) = {
        val evaluator = new Evaluator(workspacePath, mapping)

        val evaluated = evaluator.evaluate(targets).targets.filter(mapping.contains)
        assert(evaluated == expected)
      }

      def append(path: Path, txt: String) = ammonite.ops.write.append(path, txt)


      check(
        targets = OSet(jar),
        expected = OSet(resourceRoot, sourceRoot, allSources, classFiles, jar)
      )

      // Re-running with no changes results in nothing being evaluated
      check(targets = OSet(jar), expected = OSet())

      // Appending an empty string gets ignored due to file-content hashing
      append(sourceRootPath / "Foo.java", "")
      check(targets = OSet(jar), expected = OSet())

      // Appending whitespace forces a recompile, but the classfilesend up
      // exactly the same so no re-jarring.
      append(sourceRootPath / "Foo.java", " ")
      check(targets = OSet(jar), expected = OSet(sourceRoot, allSources, classFiles))

      // Appending a new class changes the classfiles, which forces us to
      // re-create the final jar
      append(sourceRootPath / "Foo.java", "\nclass FooTwo{}")
      check(targets = OSet(jar), expected = OSet(sourceRoot, allSources, classFiles, jar))

      // Tweaking the resources forces rebuild of the final jar, without
      // recompiling classfiles
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(jar), expected = OSet(resourceRoot, jar))

      // Asking for an intermediate target forces things to be build up to that
      // target only; these are re-used for any downstream targets requested
      append(sourceRootPath / "Bar.java", "\nclass BarTwo{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(classFiles), expected = OSet(sourceRoot, allSources, classFiles))
      check(targets = OSet(jar), expected = OSet(resourceRoot, jar))
      check(targets = OSet(allSources), expected = OSet())

      append(sourceRootPath / "Bar.java", "\nclass BarThree{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(resourceRoot), expected = OSet(resourceRoot))
      check(targets = OSet(allSources), expected = OSet(sourceRoot, allSources))
      check(targets = OSet(jar), expected = OSet(classFiles, jar))

      val jarContents = %%('jar, "-tf", workspacePath/'jar)(workspacePath).out.string
      val expectedJarContents =
        """META-INF/MANIFEST.MF
          |hello.txt
          |test/Bar.class
          |test/BarThree.class
          |test/BarTwo.class
          |test/Foo.class
          |test/FooTwo.class
          |""".stripMargin
      assert(jarContents == expectedJarContents)

      val executed = %%('java, "-cp", workspacePath/'jar, "test.Foo")(workspacePath).out.string
      assert(executed == (31337 + 271828) + "\n")

      println("="*20 + "Run Main" + "="*20)
      for(i <- 0 until 3){
        // Build.run is not cached, so every time we eval it it has to
        // re-evaluate
        val (runOutput, evalCount) = eval(Build.run("test.Foo"))
        assert(
          runOutput.out.string == (31337 + 271828) + "\n",
          evalCount == 1
        )
      }

      val ex = intercept[ammonite.ops.ShelloutException]{
        eval(Build.run("test.BarFour"))
      }
      assert(ex.getMessage.contains("Could not find or load main class"))

      append(
        sourceRootPath / "Bar.java",
        """
        class BarFour{
          public static void main(String[] args){
            System.out.println("New Cls!");
          }
        }
        """
      )
      val (runOutput2, evalCount2) = eval(Build.run("test.BarFour"))
      assert(
        runOutput2.out.string == "New Cls!\n",
        evalCount2 == 4
      )
      val (runOutput3, evalCount3) = eval(Build.run("test.BarFour"))
      assert(
        runOutput3.out.string == "New Cls!\n",
        evalCount3 == 1
      )


    }
  }
}