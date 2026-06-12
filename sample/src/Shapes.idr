||| A tiny module for exercising the plugin features.
module Shapes

%default total

public export
data Shape : Type where
  Circle    : (radius : Double) -> Shape
  Rectangle : (width : Double) -> (height : Double) -> Shape

||| Compute the area of a shape.
public export
area : Shape -> Double
area (Circle radius) = pi * radius * radius
area (Rectangle width height) = width * height

-- Try the intentions here:
--  * caret on `s`, Alt-Enter -> Idris: Case split
--  * caret on the hole, Alt-Enter -> Idris: Proof search / Make lemma
public export
perimeter : Shape -> Double
perimeter s = ?perimeter_rhs
