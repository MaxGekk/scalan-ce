package scalan.codegen.lms

import scalan.{ScalanCtxStaged, ScalanStaged}
import scalan.codegen.LangBackend
//import virtualization.lms.common._
//import virtualization.lms.epfl.test7._
//import virtualization.lms.util._
import scalan.codegen.GraphVizExport
import java.io.{InputStreamReader, BufferedReader, File}

trait LMSBridge[A,B] {
  val scalan: ScalanStaged with LmsBackend

  trait LMSFacadeBase {
    val lFunc: LMSFunction[A,B]
  }

  def outerTest[Ctx <: scalan.Transformer](app: LMSFunction[A,B])(in: app.Exp[A], g: scalan.ProgramGraph[Ctx]):app.Exp[B]

  class LMSFacade[Ctx <: scalan.Transformer](g: scalan.ProgramGraph[Ctx]) extends LMSFacadeBase {
    val lFunc = new LMSFunction[A,B] {
      def test(in: this.Exp[A]): this.Exp[B] = {
        outerTest(this)(in, g)
      }
    }
  }

  def getFacade[Ctx <: scalan.Transformer](g: scalan.ProgramGraph[Ctx]) = new LMSFacade[Ctx](g)
}
trait MyBridge[A,B] extends LMSBridge[A,B] {

  def outerTest[Ctx <: scalan.Transformer](lFunc: LMSFunction[A,B])(in: lFunc.Exp[A], g: scalan.ProgramGraph[Ctx]): lFunc.Exp[B] = {

    import scala.collection.Map

    val emptySymMirror = Map.empty[scalan.Exp[_], lFunc.Exp[X] forSome {type X}]
    val emptyFuncMirror = Map.empty[scalan.Exp[_], ( lFunc.Exp[X] => lFunc.Exp[Y]) forSome {type X; type Y}]

    val definitions = g.schedule



    def mirrorLambdaToLmsFunc[I, R](lam:scalan.Lambda[_,_],
                                    symMirror: Map[scalan.Exp[_], lFunc.Exp[Z] forSome {type Z}],
                                    funcMirror: Map[scalan.Exp[_],( lFunc.Exp[X] => lFunc.Exp[Y]) forSome {type X; type Y}] ) : ( lFunc.Exp[I] => lFunc.Exp[R]) = {
      val lamX = lam.x
      val f = { x: lFunc.Exp[I] =>
        val sched = lam.schedule
        val (lamExps, _, _) = mirrorDefs(sched, symMirror + ((lamX, x)), funcMirror )
        val res = if (lamExps.isEmpty) x else (lamExps.last)
        res.asInstanceOf[lFunc.Exp[R]]
      }
      f
    }


    /* Mirror block */
    def mirrorDefs(defs: List[scalan.TableEntry[_]],
                   symMirror: Map[scalan.Exp[_], lFunc.Exp[A] forSome {type A}],
                   funcMirror: Map[scalan.Exp[_],( lFunc.Exp[A] => lFunc.Exp[B]) forSome { type A; type B } ] ) : (List[lFunc.Exp[_]],
                   Map[scalan.Exp[_], lFunc.Exp[Z] forSome {type Z}],
                    Map[scalan.Exp[_],( lFunc.Exp[X] => lFunc.Exp[Y]) forSome {type X; type Y} ] ) =
    {
      val (lmsExps, finalSymMirr, finalFuncMirr) = defs.foldLeft(List.empty[lFunc.Exp[_]], symMirror, funcMirror) {
        case ((exps, symMirr, funcMirr),tp) => {
          val s = tp.sym
          tp.rhs match {
            case lam:scalan.Lambda[a, b] => {
              val f = mirrorLambdaToLmsFunc[a,b](lam, symMirr, funcMirr)
              (exps, symMirr, funcMirr + ((s, f)) )
            }
            case c@scalan.Const(_) => {
              val exp = lFunc.unitD(c.x)
              (exps ++ List(exp), symMirr + ((s, exp)), funcMirr )
            }
            case scalan.First(tuple) => {
              tuple.elem match {
                case pe: scalan.PairElem[_, _] =>
                  (scalan.createManifest(pe.ea), scalan.createManifest(pe.eb))  match {
                    case (mA:Manifest[a], mB: Manifest[b]) =>
                      val tup = symMirror(tuple).asInstanceOf[lFunc.Exp[(a,b)]]
                      val exp = lFunc.first[a,b](tup)(mA, mB)
                      (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
                  }
              }
            }
            case scalan.Second(tuple) => {
              tuple.elem match {
                case pe: scalan.PairElem[_, _] =>
                  (scalan.createManifest(pe.ea), scalan.createManifest(pe.eb))  match {
                    case (mA:Manifest[a], mB: Manifest[b]) =>
                      val tup = symMirror(tuple).asInstanceOf[lFunc.Exp[(a,b)]]
                      val exp = lFunc.second[a,b](tup)(mA, mB)
                      (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
                  }
              }
            }
            case scalan.NumericTimes(arg1, arg2, n) => {
              scalan.createManifest(arg1.elem) match {
                case (mA:Manifest[a]) =>
                  val arg1_ = symMirr(arg1).asInstanceOf[lFunc.Exp[a]]
                  val arg2_ = symMirr(arg2).asInstanceOf[lFunc.Exp[a]]
                  val n1 = n.asInstanceOf[Numeric[a]]
                  val exp = lFunc.opMult(arg1_, arg2_)(n1, mA)
                  (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
              }
            }
            case scalan.NumericPlus(arg1, arg2, n) => {
              scalan.createManifest(arg1.elem) match {
                case (mA:Manifest[a]) =>
                  val arg1_ = symMirr(arg1).asInstanceOf[lFunc.Exp[a]]
                  val arg2_ = symMirr(arg2).asInstanceOf[lFunc.Exp[a]]
                  val n1 = n.asInstanceOf[Numeric[a]]
                  val exp = lFunc.opPlus(arg1_, arg2_)(n1, mA)
                  (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
              }
            }
            case scalan.NotEqual(arg1, arg2) => {
              scalan.createManifest(arg1.elem) match {
                case (mA:Manifest[a]) =>
                  val arg1_ = symMirr(arg1).asInstanceOf[lFunc.Exp[a]]
                  val arg2_ = symMirr(arg2).asInstanceOf[lFunc.Exp[a]]
                  val exp = lFunc.opNeq[a](arg1_, arg2_)(mA)
                  (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
              }
            }
            case scalan.ArrayApply(xs, ind) => {
              scalan.createManifest(xs.elem) match {
                case (mA:Manifest[a]) =>
                  val xs_ = symMirr(xs).asInstanceOf[lFunc.Exp[Array[a]]]
                  val ind_ = symMirr(ind).asInstanceOf[lFunc.Exp[Int]]
                  val exp = lFunc.arrayGet[a](xs_, ind_)(mA)
                  (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
              }
            }
            case scalan.ArrayApplyMany(xs, idxs) => {
              (xs.elem) match {
                case (el: scalan.ArrayElem[_]) =>
                  scalan.createManifest(el.ea) match {
                  case (mA:Manifest[a]) =>
                    val xs_ = symMirr(xs).asInstanceOf[lFunc.Exp[Array[a]]]
                    val idxs_ = symMirr(idxs).asInstanceOf[lFunc.Exp[Array[Int]]]
                    val exp = lFunc.arrayGather[a](xs_, idxs_)(mA)
                    (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
                }
              }
            }
            case scalan.ArrayLength(xs) => {
              scalan.createManifest(xs.elem) match {
                case (mA:Manifest[a]) =>
                  val xs_ = symMirr(xs).asInstanceOf[lFunc.Exp[Array[a]]]
                  val exp = lFunc.arrayLength[a](xs_)(mA)
                  (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
              }
            }
            case scalan.ArrayRangeFrom0(n) => {
              val n_ = symMirr(n).asInstanceOf[lFunc.Exp[Int]]
              val exp = lFunc.indexRangeD(n_)
              (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
            }
            case scalan.ArrayZip(arg1, arg2) => {
              (arg1.elem,arg2.elem) match {
                case (el1: scalan.ArrayElem[_], el2: scalan.ArrayElem[_]) =>
                  (scalan.createManifest(el1.ea), scalan.createManifest(el2.ea)) match {
                    case (mA:Manifest[a], mB:Manifest[b]) =>
                      val arg1_ = symMirr(arg1).asInstanceOf[lFunc.Exp[Array[a]]]
                      val arg2_ = symMirr(arg2).asInstanceOf[lFunc.Exp[Array[b]]]
                      val exp = lFunc.opZip[a,b](arg1_, arg2_)(mA, mB)
                      (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
                  }
              }
            }
            case map@scalan.ArrayMap(source, lambdaSym@scalan.Def(lam:scalan.Lambda[_,_]) ) => {
              (source.elem,map.selfType) match {
                case (el: scalan.ArrayElem[_], el1: scalan.ArrayElem[_]) =>
                  (scalan.createManifest(el.ea), scalan.createManifest(el1.ea)) match {
                    case (mA:Manifest[a], mB: Manifest[b]) =>
                      val f = mirrorLambdaToLmsFunc[a,b](lam, symMirr, funcMirr)//(mA, mB)
                      val lmsSource = symMirr(source).asInstanceOf[lFunc.Exp[Array[a]]]
                      val exp = lFunc.mapArray[a,b](lmsSource, f)(mA, mB)
                      (exps ++ List(exp), symMirr + ((s,exp)), funcMirr + ((lambdaSym,f)))
                  }
              }
            }
            case filter@scalan.ArrayFilter(source, lambdaSym@scalan.Def(lam:scalan.Lambda[_,_]) ) => {
              (filter.selfType) match {
                case (el: scalan.ArrayElem[_]) =>
                  (scalan.createManifest(el.ea)) match {
                    case (mA:Manifest[a]) =>
                      val f = mirrorLambdaToLmsFunc[a,Boolean](lam, symMirr, funcMirr)//(mA, mB)
                      val lmsSource = symMirr(source).asInstanceOf[lFunc.Exp[Array[a]]]
                      val exp = lFunc.filterArray[a](lmsSource, f)(mA)
                      (exps ++ List(exp), symMirr + ((s,exp)), funcMirr + ((lambdaSym,f)))
                  }
              }
            }
            /* This is reduce */
            case scalan.ArraySum(source, monoid) => {
              (monoid, source.elem) match {
                case (scalan.IntRepPlusMonoid, el: scalan.ArrayElem[_]) => {
                  scalan.createManifest(el.ea) match {
                    case (mA: Manifest[a]) =>
                      val lmsSource = symMirr(source).asInstanceOf[lFunc.Exp[Array[a]]]
                      val exp = lFunc.reduce[a](lmsSource)(mA)
                      (exps ++ List(exp), symMirr + ((s, exp)), funcMirr)
                  }
                }
                case _ => scalan.!!!("ScalanLMSBrindge: Unfortunatelly, only Plus monoid is supported by lms ")
              }
            }
            /* This is dotSparse. Uncomment when ready!  */
            /*case scalan.dotSparse(i1,v1, i2, v2) => {
              (v1.elem) match {
                case (el: scalan.ArrayElem[_]) =>
                  (scalan.createManifest(el.ea)) match {
                    case (mA:Manifest[a]) =>
                      val i1_ = symMirr(i1).asInstanceOf[lFunc.Exp[Array[Int]]]
                      val i2_ = symMirr(i2).asInstanceOf[lFunc.Exp[Array[Int]]]
                      val v1_ = symMirr(v1).asInstanceOf[lFunc.Exp[Array[a]]]
                      val v2_ = symMirr(v2).asInstanceOf[lFunc.Exp[Array[a]]]
                      val exp = lFunc.opDotProductSV[a](i1_, v1_, i2_, v2_)(mA)
                      (exps ++ List(exp), symMirr + ((s,exp)), funcMirr )
                  }
              }
            }*/

            case _ => scalan.!!!("ScalanLMSBridge: Don't know how to mirror symbol ", s)
          }
        }
      }
      (lmsExps, finalSymMirr, finalFuncMirr)
    }

    val (lmsExps, finalSymMirror, finalFuncMirror) = mirrorDefs(definitions, emptySymMirror, emptyFuncMirror)
    val res = finalFuncMirror(g.roots.last).asInstanceOf[lFunc.Exp[A] => lFunc.Exp[B]](in)

    res
  }
}

trait LmsBackend extends LangBackend { self: ScalanStaged with GraphVizExport =>

  protected def launchProcess(launchDir: File, commandArgs: String*) {
    val builder = new ProcessBuilder(commandArgs: _*)
    val absoluteLaunchDir = launchDir.getAbsoluteFile
    builder.directory(absoluteLaunchDir)
    builder.redirectErrorStream(true)
    val proc = builder.start()
    val exitCode = proc.waitFor()
    exitCode match{
      case 0 =>
      case _ =>
        val stream = proc.getInputStream
        try {
          val sb = new StringBuilder()
          val reader = new BufferedReader(new InputStreamReader(stream))
          var line: String = reader.readLine()
          while (line != null) {
            sb.append(line).append("\n")
            line = reader.readLine()
          }
          throw new RuntimeException(s"Executing '${commandArgs.mkString(" ")}' in directory $absoluteLaunchDir returned exit code $exitCode with following output:\n$sb")
        } finally {
          stream.close()
        }
    }
  }

  def run(dir: String, fileName: String, func: Exp[_], emitGraphs: Boolean) = {
    val outDir = new File(dir)
    (emitGraphs) match {
      case true =>
        val dotFile = new File(outDir, fileName + ".dot")
        this.emitDepGraph(func, dotFile.getAbsolutePath(), false)
      case _ =>
    }

    val g0 = new PGraph(List(func))

    import java.io.PrintWriter
    import java.io.FileOutputStream

    /* LMS stuff */

    val outputSource = new File(outDir, fileName + ".scala")

    func.elem match {
      case el:FuncElem[_,_] =>
        (createManifest(el.ea), createManifest(el.eb)) match {
          case (mA:Manifest[a], mB:Manifest[b]) =>
            val bridge = new MyBridge[a, b] {
              val scalan: LmsBackend.this.type = self
            }
            val facade = bridge.getFacade(g0)
            val codegen = facade.lFunc.codegen

            codegen.emitSource[a,b](facade.lFunc.test, fileName, new PrintWriter(new FileOutputStream(outputSource.getAbsolutePath())))(mA, mB)
        }
    }


    /* Launch scalac */
    launchProcess(outDir, "scalac", outputSource.getAbsolutePath())
  }

  def createManifest[T](eA: Elem[T]) : Manifest[_] = {
    // Doesn't work for some reason, produces int instead of Int
    //    implicit val typeTag = eA.tag
    //    implicit val classTag = eA.classTag
    //    manifest[T]
    val manifest = eA match {
      case el: BaseElem[_] =>
        el.tag.tpe.toString()  match {
          case "Double" => Manifest.Double
          case "Int" => Manifest.Int
          case tpe => ???(s"Don't know how to create manifest for base type $tpe")
        }
      case el: UnitElem =>
        Manifest.Unit
      case el: PairElem[_, _] =>
        Manifest.classType(classOf[(_, _)], createManifest(el.ea), createManifest(el.eb) )
      case el: ArrayElem[_] => {
        Manifest.arrayType(createManifest(el.ea) )
      }
      case el: FuncElem[_,_] => {
        Manifest.classType(classOf[_ => _], createManifest(el.ea), createManifest(el.eb) )
      }
      case el => ???(s"Don't know how to create manifest for $el")
    }
    manifest
  }
}
