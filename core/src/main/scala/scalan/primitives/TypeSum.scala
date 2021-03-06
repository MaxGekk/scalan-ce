package scalan.primitives

import scalan.common.Lazy
import scalan.staged.{ BaseExp }
import scalan.{ ScalanExp, Scalan, ScalanSeq }
import scala.language.{ implicitConversions }

trait TypeSum { self: Scalan =>

  trait SumOps[A, B] {
    //def eA: Elem[A]
    //def eB: Elem[B]
    def isLeft: Rep[Boolean]
    def isRight: Rep[Boolean]
    def fold[R: Elem](l: Rep[A] => Rep[R], r: Rep[B] => Rep[R]): Rep[R]
  }

  implicit def pimpSum[A, B](s: Rep[(A | B)]): SumOps[A, B]

  def toLeft[A](a: Rep[A]): Rep[A | Unit] = toLeftSum[A, Unit](a)

  def toRight[A](a: Rep[A]): Rep[Unit | A] = toRightSum[Unit, A](a)

  def toLeftSum[A, B: Elem](a: Rep[A]): Rep[(A | B)]

  def toRightSum[A: Elem, B](a: Rep[B]): Rep[(A | B)]

  implicit class JoinSumOps[A:Elem](sum: Rep[A|A]) {
    def joinSum: Rep[A] = sum.fold(a => a, a => a)
  }
}

trait TypeSumSeq extends TypeSum { self: ScalanSeq =>

  def toLeftSum[A, B: Elem](a: Rep[A]): Rep[(A | B)] = Left[A, B](a)

  def toRightSum[A: Elem, B](a: Rep[B]): Rep[(A | B)] = Right[A, B](a)

  class SeqSumOps[A, B](s: Rep[(A | B)]) extends SumOps[A, B] {
    //def eA = element[A]; def eB = element[B]
    def fold[R: Elem](l: Rep[A] => Rep[R], r: Rep[B] => Rep[R]): Rep[R] = s.fold(l, r)
    def isLeft = s.isLeft
    def isRight = s.isRight
  }
  implicit def pimpSum[A, B](s: Rep[(A | B)]): SumOps[A, B] = new SeqSumOps[A, B](s)

}

trait TypeSumExp extends TypeSum with BaseExp { self: ScalanExp =>

  case class Left[A, B](left: Exp[A])(implicit val eB: Elem[B]) extends BaseDef[(A | B)]()(sumElement(left.elem, eB)) {
    override def mirror(t: Transformer) = Left[A, B](t(left))
    lazy val uniqueOpId = name(selfType)
  }

  case class Right[A, B](right: Exp[B])(implicit val eA: Elem[A]) extends BaseDef[(A | B)]()(sumElement(eA, right.elem)) {
    override def mirror(t: Transformer) = Right[A, B](t(right))
    lazy val uniqueOpId = name(selfType)
  }

  def toLeftSum[A, B: Elem](a: Rep[A]): Rep[(A | B)] = withElemOf(a) { implicit e => Left[A, B](a) }
  def toRightSum[A: Elem, B](b: Rep[B]): Rep[(A | B)] = withElemOf(b) { implicit e => Right[A, B](b) }

  case class IsLeft[A, B](sum: Exp[(A | B)]) extends BaseDef[Boolean] {
    override def mirror(t: Transformer) = IsLeft(t(sum))
    // removing leads to compilation error
    override val selfType = BoolElement
    lazy val uniqueOpId = name(sum.elem.eLeft, sum.elem.eRight)
  }

  case class IsRight[A, B](sum: Exp[(A | B)]) extends BaseDef[Boolean] {
    override def mirror(t: Transformer) = IsRight(t(sum))
    // removing leads to compilation error
    override val selfType = BoolElement
    lazy val uniqueOpId = name(sum.elem.eLeft, sum.elem.eRight)
  }

  case class SumFold[A, B, R](sum: Exp[(A | B)], left: Exp[A => R], right: Exp[B => R])(implicit selfType: Elem[R]) extends BaseDef[R] {
    override def mirror(t: Transformer) = SumFold(t(sum), t(left), t(right))
    lazy val uniqueOpId = name(sum.elem.eLeft, sum.elem.eRight)
  }

  class SumOpsExp[A, B](s: Rep[(A | B)]) extends SumOps[A, B] {
    implicit def eA = s.elem.eLeft
    implicit def eB = s.elem.eRight
    def fold[R: Elem](l: Rep[A] => Rep[R], r: Rep[B] => Rep[R]): Rep[R] = SumFold(s, fun(l), fun(r))
    def isLeft = IsLeft(s)
    def isRight = IsRight(s)
  }
  implicit def pimpSum[A, B](s: Rep[(A | B)]): SumOps[A, B] = new SumOpsExp[A, B](s)

  object IsJoinSum {
    def unapply[T](d: Def[T]): Option[Rep[Source] forSome { type Source }] = d match {
      case SumFold(source, Def(Lambda(l,_,_,_)), Def(Lambda(r,_,_,_))) if l.isIdentity && r.isIdentity => Some(source)
      case _ => None
    }
  }

  override def rewriteDef[T](d: Def[T]) = d match {
    case First(Def(foldD: SumFold[a,b,(T,r2)]@unchecked)) => {
      foldD.sum.fold(a => foldD.left(a)._1, b => foldD.right(b)._1)(d.selfType)
    }
    case Second(Def(foldD: SumFold[a,b,(r1,T)]@unchecked)) => {
      foldD.sum.fold(a => foldD.left(a)._2, b => foldD.right(b)._2)(d.selfType)
    }
    case foldD: SumFold[a,b,T] => foldD.sum match {
      case Def(IfThenElse(cond, Def(Left(left: Rep[a])), Def(Right(right: Rep[b])))) if !d.selfType.isEntityType => {
        // this rule is only applied when result of fold is base type.
        // Otherwise it yields stack overflow as this rule is mutually recursive with lifting Views over IfThenElse
          IF(cond) THEN {
            foldD.left(left)
          } ELSE {
            foldD.right(right)
          }
      }
      case Def(view: SumView[a1,a2,b1,b2]) if !d.selfType.isEntityType => {
        view.source.fold(x => foldD.left.asRep[b1 => T](view.iso1.to(x)), y => foldD.right.asRep[b2 => T](view.iso2.to(y)))(d.selfType)
      }
      case Def(join @ IsJoinSum(sum)) => {
        val source = sum.asRep[(a | b) | (a | b)]
        implicit val eT = foldD.selfType
        source.fold(
          x => x.fold(a => foldD.left(a), b => foldD.right(b)),
          y => y.fold(a => foldD.left(a), b => foldD.right(b)))
      }
      case Def(Left(left: Rep[a])) => {
        implicit val eLeft = left.elem
        foldD.left.asRep[a => T](left)
      }
      case Def(Right(right: Rep[a])) => {
        implicit val eRight = right.elem
        foldD.right.asRep[a => T](right)
      }
      case _ => super.rewriteDef(d)
    }
    case IsLeft(Def(Left(_))) => true
    case IsLeft(Def(Right(_))) => false
    case IsRight(Def(Left(_))) => false
    case IsRight(Def(Right(_))) => true
    case _ => super.rewriteDef(d)
  }
}