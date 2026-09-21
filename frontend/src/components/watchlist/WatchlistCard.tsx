import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import type { WatchlistView } from '@/types/watchlist';

interface WatchlistCardProps {
  watchlist: WatchlistView;
  selected: boolean;
  onSelect: (id: number) => void;
}

/** 清单卡片：名称 + 标的数徽章 + 备注 + 查看/查看中按钮。 */
export function WatchlistCard({ watchlist, selected, onSelect }: WatchlistCardProps) {
  return (
    <Card data-testid={`watchlist-card-${watchlist.id}`} className={selected ? 'ring-2 ring-ring' : ''}>
      <CardHeader>
        <CardTitle className="text-base">{watchlist.name}</CardTitle>
        <CardAction>
          <Badge variant="secondary" data-testid={`watchlist-item-count-${watchlist.id}`}>
            {watchlist.items.length} 标的
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent>
        {watchlist.remark ? <p className="text-sm text-muted-foreground">{watchlist.remark}</p> : null}
        <div className="mt-3">
          <Button
            size="sm"
            variant={selected ? 'default' : 'outline'}
            onClick={() => onSelect(watchlist.id)}
            data-testid={`watchlist-select-${watchlist.id}`}
          >
            {selected ? '查看中' : '查看'}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
