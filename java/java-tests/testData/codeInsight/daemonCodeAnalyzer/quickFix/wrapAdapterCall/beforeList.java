// "Adapt using 'Collections.singletonList()'" "true"
import java.util.*;

class Test {

  void m(long l) {
    List<Long> list = <caret>l;
  }

}