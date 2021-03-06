package scalan.compilation

import java.io.{File, PrintWriter}
import java.lang.reflect.Method

import _root_.scalan.ScalanExp
import scalan.util.{FileUtil, ScalaNameUtil}

trait GraphVizExport { self: ScalanExp =>

  protected def quote(x: Any) = "\"" + x + "\""

  protected def nodeColor(sym: Exp[_]): String = sym.elem match {
    case _: ViewElem[_, _] => "green"
    case _: FuncElem[_, _] => "magenta"
    case _ => "grey"
  }

  protected def nodeLabel(str: String) = s"label=${quote(str)}"

  protected def emitNode(sym: Exp[_], rhs: Def[_])(implicit stream: PrintWriter) = {
    rhs match {
      case l: Lambda[_, _] =>
        val x = l.x
        stream.println(quote(x) + " [")
        stream.println(nodeLabel(x.toStringWithType))
        stream.println(s"color=${nodeColor(x)}")
        stream.println("]")
      case _ =>
    }
    stream.println(quote(sym) + " [")
    stream.println(nodeLabel(sym.toString + " = " + formatDef(rhs)))
    stream.println(s"shape=box,color=${nodeColor(sym)},tooltip=${quote(sym.toStringWithType)}")
    stream.println("]")
  }

  protected def formatDef(d: Def[_]): String = d match {
    case l: Lambda[_, _] => s"\\${l.x} -> ${l.y match { case Def(b) => formatDef(b) case y => y.toString}}"
    case Apply(f, arg) => s"$f($arg)"
    case MethodCall(obj, method, args) =>
      val className = ScalaNameUtil.cleanNestedClassName(method.getDeclaringClass.getName)
      val methodName = ScalaNameUtil.cleanScalaName(method.getName)
      s"$obj.$className.$methodName(${args.mkString(", ")})"
    case Tup(a, b) => s"($a, $b)"
    case First(pair) => s"$pair._1"
    case Second(pair) => s"$pair._2"
    case IfThenElse(c, t, e) => s"if ($c) $t else $e"
    case LoopUntil(start, step, isMatch) => s"from $start do $step until $isMatch"
    case ApplyBinOp(op, lhs, rhs) => s"$lhs ${op.opName} $rhs"
    case ApplyUnOp(op, arg) => op match {
      case NumericToFloat(_) => s"$arg.toFloat"
      case NumericToDouble(_) => s"$arg.toDouble"
      case NumericToInt(_) => s"$arg.toInt"
      case _ => s"${op.opName} $arg"
    }
    case _ => d.toString
  }

  private def emitDeps(sym: Exp[_], deps: List[Exp[_]], dotted: Boolean)(implicit stream: PrintWriter) = {
    for (dep <- deps) {
      val depLabel = dep.toString //dep.isVar match { case true => dep.toStringWithType case _ => dep.toString }
      val params = if (dotted) " [style=dotted]" else ""
      stream.println(s"${quote(depLabel)} -> ${quote(sym)}$params")
    }
  }

  def emitDepGraph(d: Def[_], file: File, landscape: Boolean): Unit =
    emitDepGraph(dep(d), file, landscape)
  def emitDepGraph(start: Exp[_], file: File, landscape: Boolean = false): Unit =
    emitDepGraph(List(start), file, landscape)
  def emitDepGraph(ss: List[Exp[_]], file: File, landscape: Boolean): Unit =
    FileUtil.withFile(file) {
      emitDepGraph(ss, _, landscape)
    }

  private def lambdaDeps(l: Lambda[_, _]): (List[Exp[_]], List[Exp[_]]) = l.y match {
    case Def(l1: Lambda[_, _]) =>
      val (ds, vs) = lambdaDeps(l1)
      (ds, l.x :: vs)
    case _ => (dep(l.y), List(l.x))
  }

  private def emitDepGraph(ss: List[Exp[_]], stream: PrintWriter, landscape: Boolean): Unit = {
    stream.println("digraph G {")

    val deflist = buildScheduleForResult(ss, dep)

    if (landscape) {
      stream.println("rankdir=LR")
    }

    val lambdaBodies: Map[Exp[_], Exp[_]] = (deflist collect {
      case TableEntry(s, lam: Lambda[_, _]) => (lam.y, s)
    }).toMap

    for (tp @ TableEntry(sym, rhs) <- deflist) {
      if (!lambdaBodies.contains(sym)) {
        val (deps, lambdaVars) = rhs match {
          case l: Lambda[_, _] => lambdaDeps(l)
          case _ => (dep(rhs), Nil)
        }
        // emit node
        emitNode(sym, rhs)(stream)

        emitDeps(sym, deps, false)(stream)
        emitDeps(sym, lambdaVars, true)(stream)

        // emit lambda refs
        // bad on large graphs!
//        tp.lambda match {
//          case Some(lam) =>
//            stream.println(s"${quote(tp.sym)} -> ${quote(lam)} [style=dotted,color=grey]")
//          case _ =>
//        }

      } else {
        // skip lambda bodies
      }
    }

    stream.println("}")
    stream.close()
  }
}
