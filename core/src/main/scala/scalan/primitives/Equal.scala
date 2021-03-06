package scalan.primitives

import scalan.staged.BaseExp
import scalan.{Scalan, ScalanExp}

trait Equal extends UnBinOps { self: Scalan =>
  case class Equals[A]() extends BinOp[A, Boolean]("==", _ == _)

  implicit class EqualOps[A](x: Rep[A]) {
    def ===(y: Rep[A]): Rep[Boolean] = Equals[A].apply(x, y)
    def !==(y: Rep[A]): Rep[Boolean] = !Equals[A].apply(x, y)
  }
}

// Note: not currently true because Random exists. Consider a way to check when it _is_ true?
trait EqualExp extends Equal with BaseExp { self: ScalanExp =>
//  override def rewriteDef[T](d: Def[T]) = d match {
//    case ApplyBinOp(Equals(), x, y) if x == y => Const(true)
//    case _ => super.rewriteDef(d)
//  }
}
