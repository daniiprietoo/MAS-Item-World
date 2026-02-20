package helper;
import java.io.Serializable;
import java.util.Objects;

public class Position implements Serializable, Cloneable {

    public int x;
    public int y;

    public Position(int x, int y)
    {
        this.x = x;
        this.y = y;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        return (this.x == ((Position) o).x) && (this.y == ((Position) o).y);
    }

    @Override
	public Object clone() throws CloneNotSupportedException {
	    return new Position(x,y);
	}

    @Override
    public String toString()
    {
        return String.format("({%d},{%d})", x, y);
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }
}
