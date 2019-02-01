package bio.overture.ego.utils;

import static lombok.AccessLevel.PRIVATE;

import com.google.common.base.Joiner;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = PRIVATE)
public class Joiners {

  public static final Joiner COMMA = Joiner.on(",");
}